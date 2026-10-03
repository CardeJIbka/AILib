package com.cardejibka.ailib.downloader;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.api.ModelFile;
import com.cardejibka.ailib.api.ModelHandle;
import com.cardejibka.ailib.api.ModelSpec;
import com.cardejibka.ailib.config.AiLibConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Orchestrates the background download of natives and models. Every artifact is an independent
 * async task. A failed download is NOT cached forever: for models use {@code handle.retry()} or
 * register again; natives are retried automatically, but at most once a minute.
 * <p>
 * Several mods may share one file: models whose file name, url and sha256 are identical reuse it
 * (reference counted); the same file name with different content is reported as a collision.
 */
public final class AiLibBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Bootstrap");
    private static final long NATIVE_RETRY_COOLDOWN_MS = 60_000;

    private static final Object LOCK = new Object();
    private static final Map<String, CompletableFuture<Boolean>> NATIVE_TASKS = new ConcurrentHashMap<>();
    private static final Map<String, Long> NATIVE_FAILED_AT = new ConcurrentHashMap<>();
    private static final Map<String, ModelEntry> MODELS = new ConcurrentHashMap<>();
    // lowercase file name -> who uses it and what it is supposed to contain. Guarded by LOCK.
    private static final Map<String, FileClaim> FILE_CLAIMS = new HashMap<>();
    // One lock per target file so two models sharing a file never download it concurrently.
    private static final Map<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private AiLibBootstrap() {
    }

    // ---------- Native binaries ----------

    public static boolean isNativeReady(NativeConfig.AiModule module) {
        return module.resolveExecutable() != null;
    }

    public static boolean hasNativeFailed(NativeConfig.AiModule module) {
        Long failedAt = NATIVE_FAILED_AT.get(module.getId());
        return failedAt != null && System.currentTimeMillis() - failedAt < NATIVE_RETRY_COOLDOWN_MS;
    }

    public static CompletableFuture<Boolean> ensureNativeReady(NativeConfig.AiModule module) {
        if (isNativeReady(module)) return CompletableFuture.completedFuture(true);
        if (hasNativeFailed(module)) return CompletableFuture.completedFuture(false);

        String id = module.getId();
        CompletableFuture<Boolean> mine = new CompletableFuture<>();
        CompletableFuture<Boolean> existing = NATIVE_TASKS.putIfAbsent(id, mine);
        if (existing != null) return existing;

        String taskId = "native:" + id;
        String label = "Downloading " + id;
        try {
            CompletableFuture.supplyAsync(() -> runNativeDownload(module, taskId, label), AiLibExecutors.DOWNLOAD_EXECUTOR)
                    .whenComplete((ok, ex) -> {
                        boolean success = ex == null && Boolean.TRUE.equals(ok);
                        if (success) NATIVE_FAILED_AT.remove(id);
                        else NATIVE_FAILED_AT.put(id, System.currentTimeMillis());
                        NATIVE_TASKS.remove(id);
                        mine.complete(success);
                    });
        } catch (RejectedExecutionException e) {
            NATIVE_FAILED_AT.put(id, System.currentTimeMillis());
            NATIVE_TASKS.remove(id);
            mine.complete(false);
        }
        return mine;
    }

    private static boolean runNativeDownload(NativeConfig.AiModule module, String taskId, String label) {
        try {
            Path moduleDir = module.getDir();
            Files.createDirectories(moduleDir);
            if (module.resolveExecutable() != null) {
                ProgressBus.publishFinished(taskId, label, true);
                return true;
            }

            Path archivePath = moduleDir.resolve(module.getArchiveName());
            FileDownloader.download(module.getDownloadUrl(), archivePath, module.getSha256(),
                    (done, total) -> ProgressBus.publishProgress(taskId, label, done, total));

            NativeInstaller.extract(archivePath, moduleDir, module.isStripRootFolder());
            try {
                Files.deleteIfExists(archivePath);
            } catch (Exception ignored) {
            }
            // The marker is written ONLY after a successful extraction, so an interrupted
            // install never looks like a ready native.
            module.markInstalled();

            boolean ok = module.resolveExecutable() != null;
            if (!ok) {
                LOGGER.error("{} not found after extraction for module {}", module.getExpectedFile(), module.getId());
            }
            ProgressBus.publishFinished(taskId, label, ok);
            return ok;
        } catch (Exception e) {
            LOGGER.error("Could not prepare native {}: {}", module.getId(), e.getMessage());
            ProgressBus.publishFinished(taskId, label, false);
            return false;
        }
    }

    // ---------- Models ----------

    private static final class FileClaim {
        final String url;
        final String sha256;
        final Set<String> owners = new HashSet<>();

        FileClaim(String url, String sha256) {
            this.url = url;
            this.sha256 = sha256;
        }

        boolean sameContent(ModelFile f) {
            return Objects.equals(url, f.url()) && Objects.equals(sha256, f.sha256());
        }
    }

    private static final class ModelEntry implements ModelHandle {
        final ModelSpec spec;
        volatile State state = State.DOWNLOADING;
        volatile FailureReason failureReason;
        volatile String failureMessage;
        volatile CompletableFuture<Boolean> future = new CompletableFuture<>();

        ModelEntry(ModelSpec spec) {
            this.spec = spec;
        }

        void reset() {
            state = State.DOWNLOADING;
            failureReason = null;
            failureMessage = null;
            future = new CompletableFuture<>();
        }

        void succeed() {
            state = State.READY;
            future.complete(true);
        }

        void fail(FailureReason reason, String message) {
            LOGGER.error("Model '{}': {}", spec.id(), message);
            failureReason = reason;
            failureMessage = message;
            state = State.FAILED;
            future.complete(false);
        }

        @Override public ModelSpec spec() { return spec; }
        @Override public State state() { return state; }
        @Override public FailureReason failureReason() { return failureReason; }
        @Override public String failureMessage() { return failureMessage; }
        @Override public CompletableFuture<Boolean> future() { return future; }
        @Override public Path path() { return NativeConfig.getModelsDir().resolve(spec.fileName()); }

        @Override
        public float progress() {
            if (state == State.READY) return 1f;
            int p = ProgressBus.getLastPercent("model:" + spec.id());
            return p >= 0 ? p / 100f : -1f;
        }

        @Override public boolean retry() { return AiLibBootstrap.retry(this); }
        @Override public boolean delete() { return AiLibBootstrap.delete(this); }
    }

    /**
     * Registers a model and starts downloading it. Idempotent by id. Never throws: a blocked
     * domain or a file-name collision is reported as state FAILED plus a reason. Registering a
     * FAILED model again is a retry.
     */
    public static ModelHandle register(ModelSpec spec) {
        synchronized (LOCK) {
            ModelEntry existing = MODELS.get(spec.id());
            if (existing != null) {
                if (existing.state == ModelHandle.State.FAILED) {
                    launch(existing);
                } else if (!existing.spec.equals(spec)) {
                    LOGGER.warn("Model '{}' is already registered with different parameters (url/sha256/files); "
                            + "the new specification was ignored", spec.id());
                }
                return existing;
            }
            ModelEntry entry = new ModelEntry(spec);
            MODELS.put(spec.id(), entry);
            launch(entry);
            return entry;
        }
    }

    public static ModelHandle getModel(String id) {
        return MODELS.get(id);
    }

    public static List<ModelHandle> getModels() {
        return List.copyOf(MODELS.values());
    }

    public static boolean isModelReady(ModelSpec spec) {
        ModelEntry entry = MODELS.get(spec.id());
        return entry != null && entry.state == ModelHandle.State.READY;
    }

    private static boolean retry(ModelEntry entry) {
        synchronized (LOCK) {
            if (entry.state != ModelHandle.State.FAILED || MODELS.get(entry.spec.id()) != entry) return false;
            launch(entry);
            return true;
        }
    }

    private static boolean delete(ModelEntry entry) {
        synchronized (LOCK) {
            if (entry.state == ModelHandle.State.DOWNLOADING || entry.state == ModelHandle.State.VERIFYING) {
                return false;
            }
            Path dir = NativeConfig.getModelsDir();
            boolean ok = true;
            for (ModelFile f : entry.spec.allFiles()) {
                String key = f.fileName().toLowerCase(Locale.ROOT);
                boolean lastOwner = true;
                FileClaim claim = FILE_CLAIMS.get(key);
                if (claim != null) {
                    claim.owners.remove(entry.spec.id());
                    if (claim.owners.isEmpty()) FILE_CLAIMS.remove(key);
                    else lastOwner = false; // another model still uses this file
                }
                if (lastOwner) {
                    try {
                        Files.deleteIfExists(dir.resolve(f.fileName()));
                        Files.deleteIfExists(dir.resolve(f.fileName() + ".part"));
                    } catch (Exception e) {
                        LOGGER.warn("Could not delete {}: {}", f.fileName(), e.getMessage());
                        ok = false;
                    }
                }
            }
            MODELS.remove(entry.spec.id(), entry);
            return ok;
        }
    }

    private static void releaseClaims(String modelId, List<String> keys) {
        for (String key : keys) {
            FileClaim claim = FILE_CLAIMS.get(key);
            if (claim == null) continue;
            claim.owners.remove(modelId);
            if (claim.owners.isEmpty()) FILE_CLAIMS.remove(key);
        }
    }

    /** Must be called while holding LOCK. */
    private static void launch(ModelEntry entry) {
        entry.reset();
        ModelSpec spec = entry.spec;
        AiLibConfig cfg = AiLibConfig.get();

        for (ModelFile f : spec.allFiles()) {
            String host = URI.create(f.url()).getHost();
            if (!cfg.isDomainAllowed(host)) {
                entry.fail(ModelHandle.FailureReason.DOMAIN_BLOCKED, "Domain '" + host + "' is not in the allow-list "
                        + "(config/ailib.json -> allowedModelDownloadDomains). Model '" + spec.id()
                        + "' will not be downloaded until the domain is allowed explicitly.");
                return;
            }
        }

        List<String> joined = new ArrayList<>();
        for (ModelFile f : spec.allFiles()) {
            String key = f.fileName().toLowerCase(Locale.ROOT);
            FileClaim claim = FILE_CLAIMS.get(key);
            if (claim == null) {
                claim = new FileClaim(f.url(), f.sha256());
                FILE_CLAIMS.put(key, claim);
            } else if (!claim.sameContent(f)) {
                String owner = claim.owners.stream().findFirst().orElse("?");
                releaseClaims(spec.id(), joined);
                entry.fail(ModelHandle.FailureReason.FILENAME_COLLISION, "File name '" + f.fileName()
                        + "' is already used by model '" + owner + "' with different content (url/sha256). "
                        + "Give the models different file names, or use identical url and sha256 to share the file.");
                return;
            }
            if (claim.owners.add(spec.id())) joined.add(key);
        }

        // Fast path: everything is on disk and there is nothing to verify, so the model is ready right away.
        Path dir = NativeConfig.getModelsDir();
        boolean allPresent = true;
        boolean anyHash = false;
        for (ModelFile f : spec.allFiles()) {
            if (!Files.exists(dir.resolve(f.fileName()))) allPresent = false;
            if (f.sha256() != null) anyHash = true;
        }
        if (allPresent && !anyHash) {
            entry.succeed();
            return;
        }

        try {
            CompletableFuture.runAsync(() -> runModelDownload(entry), AiLibExecutors.DOWNLOAD_EXECUTOR);
        } catch (RejectedExecutionException e) {
            entry.fail(ModelHandle.FailureReason.UNKNOWN, "The download pool has been shut down");
        }
    }

    private static void runModelDownload(ModelEntry entry) {
        ModelSpec spec = entry.spec;
        String taskId = "model:" + spec.id();
        String finishLabel = "Downloading model " + spec.fileName();
        try {
            Path dir = NativeConfig.getModelsDir().toAbsolutePath().normalize();
            Files.createDirectories(dir);

            for (ModelFile f : spec.allFiles()) {
                Path target = dir.resolve(f.fileName()).normalize();
                if (!target.startsWith(dir)) {
                    throw new IllegalStateException("Model path escapes the models directory: " + f.fileName());
                }
                Object fileLock = FILE_LOCKS.computeIfAbsent(target.toString(), k -> new Object());
                synchronized (fileLock) {
                    ensureFile(entry, f, target, taskId);
                }
            }
            ProgressBus.publishFinished(taskId, finishLabel, true);
            entry.succeed();
        } catch (DownloadException e) {
            ProgressBus.publishFinished(taskId, finishLabel, false);
            entry.fail(switch (e.kind()) {
                case NETWORK -> ModelHandle.FailureReason.NETWORK;
                case HTTP_ERROR -> ModelHandle.FailureReason.HTTP_ERROR;
                case INCOMPLETE -> ModelHandle.FailureReason.INCOMPLETE;
                case HASH_MISMATCH -> ModelHandle.FailureReason.HASH_MISMATCH;
            }, e.getMessage());
        } catch (Throwable t) {
            ProgressBus.publishFinished(taskId, finishLabel, false);
            entry.fail(ModelHandle.FailureReason.UNKNOWN, String.valueOf(t.getMessage()));
        }
    }

    /** Makes sure one file is present and valid; runs under the per-file lock. */
    private static void ensureFile(ModelEntry entry, ModelFile f, Path target, String taskId) throws DownloadException {
        if (Files.exists(target)) {
            if (f.sha256() == null) return;
            entry.state = ModelHandle.State.VERIFYING;
            if (sha256Matches(target, f.sha256())) return;
            LOGGER.warn("File {} failed the sha256 check; downloading it again", f.fileName());
        }
        entry.state = ModelHandle.State.DOWNLOADING;
        String label = "Downloading model " + f.fileName();
        FileDownloader.download(f.url(), target, f.sha256(),
                (done, total) -> ProgressBus.publishProgress(taskId, label, done, total));
    }

    private static boolean sha256Matches(Path target, String expected) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[256 * 1024];
            try (var in = Files.newInputStream(target)) {
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected);
        } catch (Exception e) {
            return false;
        }
    }
}

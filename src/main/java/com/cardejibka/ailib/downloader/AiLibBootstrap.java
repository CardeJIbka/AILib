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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Оркестрирует фоновую загрузку нативов и моделей. Каждый артефакт — независимый
 * async-таск. Провалившаяся загрузка НЕ кэшируется навсегда: для моделей повтор —
 * {@code handle.retry()} или повторная регистрация, для нативов — автоматически,
 * но не чаще раза в минуту.
 */
public final class AiLibBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Bootstrap");
    private static final long NATIVE_RETRY_COOLDOWN_MS = 60_000;

    private static final Object LOCK = new Object();
    private static final Map<String, CompletableFuture<Boolean>> NATIVE_TASKS = new ConcurrentHashMap<>();
    private static final Map<String, Long> NATIVE_FAILED_AT = new ConcurrentHashMap<>();
    private static final Map<String, ModelEntry> MODELS = new ConcurrentHashMap<>();
    // fileName (lowercase) -> id модели-владельца; ловит коллизии между разными модами.
    private static final Map<String, String> FILE_NAME_OWNER = new ConcurrentHashMap<>();

    private AiLibBootstrap() {
    }

    // ---------- Нативные бинарники ----------

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
        String label = "Загрузка " + id;
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
            // Маркер пишется ТОЛЬКО после успешной распаковки — обрыв посреди не даёт "готовый" полунатив.
            module.markInstalled();

            boolean ok = module.resolveExecutable() != null;
            if (!ok) {
                LOGGER.error("После распаковки не найден {} для модуля {}", module.getExpectedFile(), module.getId());
            }
            ProgressBus.publishFinished(taskId, label, ok);
            return ok;
        } catch (Exception e) {
            LOGGER.error("Не удалось подготовить натив {}: {}", module.getId(), e.getMessage());
            ProgressBus.publishFinished(taskId, label, false);
            return false;
        }
    }

    // ---------- Модели ----------

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
            LOGGER.error("Модель '{}': {}", spec.id(), message);
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
     * Регистрирует модель и запускает загрузку. Идемпотентно по id. Никогда не бросает:
     * запрещённый домен / коллизия имени приходят как состояние FAILED + причина.
     * Повторная регистрация упавшей модели = повторная попытка.
     */
    public static ModelHandle register(ModelSpec spec) {
        synchronized (LOCK) {
            ModelEntry existing = MODELS.get(spec.id());
            if (existing != null) {
                if (existing.state == ModelHandle.State.FAILED) {
                    launch(existing);
                } else if (!existing.spec.equals(spec)) {
                    LOGGER.warn("Модель '{}' уже зарегистрирована с другими параметрами (url/sha256/файлы) — "
                            + "новая спецификация проигнорирована", spec.id());
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
                try {
                    Files.deleteIfExists(dir.resolve(f.fileName()));
                    Files.deleteIfExists(dir.resolve(f.fileName() + ".part"));
                } catch (Exception e) {
                    LOGGER.warn("Не удалось удалить {}: {}", f.fileName(), e.getMessage());
                    ok = false;
                }
                FILE_NAME_OWNER.remove(f.fileName().toLowerCase(Locale.ROOT), entry.spec.id());
            }
            MODELS.remove(entry.spec.id(), entry);
            return ok;
        }
    }

    /** Вызывается под LOCK. */
    private static void launch(ModelEntry entry) {
        entry.reset();
        ModelSpec spec = entry.spec;
        AiLibConfig cfg = AiLibConfig.get();

        for (ModelFile f : spec.allFiles()) {
            String host = URI.create(f.url()).getHost();
            if (!cfg.isDomainAllowed(host)) {
                entry.fail(ModelHandle.FailureReason.DOMAIN_BLOCKED, "Домен '" + host + "' не входит в allow-list "
                        + "(config/ailib.json -> allowedModelDownloadDomains). Модель '" + spec.id()
                        + "' не будет скачана, пока домен не разрешён явно.");
                return;
            }
        }

        List<String> claimed = new ArrayList<>();
        for (ModelFile f : spec.allFiles()) {
            String key = f.fileName().toLowerCase(Locale.ROOT);
            String owner = FILE_NAME_OWNER.putIfAbsent(key, spec.id());
            if (owner == null) {
                claimed.add(key);
            } else if (!owner.equals(spec.id())) {
                claimed.forEach(k -> FILE_NAME_OWNER.remove(k, spec.id()));
                entry.fail(ModelHandle.FailureReason.FILENAME_COLLISION, "Коллизия имени файла '" + f.fileName()
                        + "': уже занят моделью '" + owner + "', а сейчас запрошен моделью '" + spec.id()
                        + "'. Дайте моделям разные fileName.");
                return;
            }
        }

        // Быстрый путь: всё на диске и проверять нечего — готово без обращения к пулу.
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
            entry.fail(ModelHandle.FailureReason.UNKNOWN, "Пул загрузок остановлен");
        }
    }

    private static void runModelDownload(ModelEntry entry) {
        ModelSpec spec = entry.spec;
        String taskId = "model:" + spec.id();
        String finishLabel = "Загрузка модели " + spec.fileName();
        try {
            Path dir = NativeConfig.getModelsDir().toAbsolutePath().normalize();
            Files.createDirectories(dir);

            for (ModelFile f : spec.allFiles()) {
                Path target = dir.resolve(f.fileName()).normalize();
                if (!target.startsWith(dir)) {
                    throw new IllegalStateException("Путь модели выходит за пределы папки моделей: " + f.fileName());
                }
                if (Files.exists(target)) {
                    if (f.sha256() == null) continue;
                    entry.state = ModelHandle.State.VERIFYING;
                    if (sha256Matches(target, f.sha256())) continue;
                    LOGGER.warn("Файл {} не прошёл проверку sha256 — перекачиваю", f.fileName());
                }
                entry.state = ModelHandle.State.DOWNLOADING;
                String label = "Загрузка модели " + f.fileName();
                FileDownloader.download(f.url(), target, f.sha256(),
                        (done, total) -> ProgressBus.publishProgress(taskId, label, done, total));
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

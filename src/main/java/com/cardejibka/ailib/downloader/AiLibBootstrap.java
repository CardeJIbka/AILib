package com.cardejibka.ailib.downloader;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.api.ModelSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Оркестрирует фоновую загрузку нативов и моделей.
 * <p>
 * Главная идея (в отличие от прошлой версии): каждый артефакт — свой независимый
 * async-таск, который запускается сразу, как только про него узнали, а не единым
 * блокирующим "скачать всё, потом стартовать". Игра не ждёт — она уже играбельна,
 * пока в фоне параллельно (см. {@link AiLibExecutors#DOWNLOAD_EXECUTOR}, пул
 * ограничен по размеру) тянутся нужные файлы. Прогресс каждого таска летит в
 * {@link ProgressBus}, откуда его на клиенте подхватывает HUD.
 * <p>
 * Регистрация модели (или запрос натива) в любой момент — хоть в onInitialize
 * другого мода, хоть по требованию игрока — просто добавляет новый параллельный
 * таск, не мешая уже идущим. Повторная регистрация одного и того же id — no-op,
 * возвращает существующий future.
 */
public final class AiLibBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Bootstrap");

    private static final Map<String, CompletableFuture<Boolean>> NATIVE_TASKS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Boolean>> MODEL_TASKS = new ConcurrentHashMap<>();
    // Чтобы поймать коллизию: два разных ModelSpec с одинаковым именем файла на диске.
    private static final Map<String, String> FILE_NAME_OWNER = new ConcurrentHashMap<>();

    private AiLibBootstrap() {
    }

    // ---------- Нативные бинарники ----------

    public static boolean isNativeReady(NativeConfig.AiModule module) {
        try {
            return Files.exists(module.getDir().resolve(module.getExpectedFile()));
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    /** 0..100, либо -1 если задача ещё не запускалась (например, просто не читалась ProgressBus). */
    public static CompletableFuture<Boolean> ensureNativeReady(NativeConfig.AiModule module) {
        return NATIVE_TASKS.computeIfAbsent(module.getId(), id -> {
            if (isNativeReady(module)) {
                return CompletableFuture.completedFuture(true);
            }
            String taskId = "native:" + id;
            String label = "Загрузка " + id;
            return CompletableFuture.supplyAsync(() -> runNativeDownload(module, taskId, label), AiLibExecutors.DOWNLOAD_EXECUTOR);
        });
    }

    private static boolean runNativeDownload(NativeConfig.AiModule module, String taskId, String label) {
        try {
            Path moduleDir = module.getDir();
            Files.createDirectories(moduleDir);
            Path expectedFile = moduleDir.resolve(module.getExpectedFile());
            if (Files.exists(expectedFile)) {
                ProgressBus.publishFinished(taskId, label, true);
                return true;
            }

            Path archivePath = moduleDir.resolve(module.getArchiveName());
            boolean downloaded = FileDownloader.download(module.getDownloadUrl(), archivePath, null,
                    (done, total) -> ProgressBus.publishProgress(taskId, label, done, total));
            if (!downloaded) {
                ProgressBus.publishFinished(taskId, label, false);
                return false;
            }

            NativeInstaller.extract(archivePath, moduleDir, module.isStripRootFolder());
            try {
                Files.deleteIfExists(archivePath);
            } catch (Exception ignored) {
            }

            boolean ok = Files.exists(expectedFile);
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

    public static boolean isModelReady(ModelSpec spec) {
        return Files.exists(NativeConfig.getModelsDir().resolve(spec.fileName()));
    }

    /**
     * Регистрирует модель и сразу запускает (или переиспользует) фоновую загрузку.
     * Идемпотентно по {@code spec.id()} — повторный вызов с тем же id не плодит
     * повторных скачиваний, просто отдаёт тот же future.
     *
     * @throws IllegalStateException если тот же fileName уже занят другим id — это
     *         обычно значит, что два разных мода выбрали одинаковое имя файла для
     *         разных моделей, и нужно переименовать одну из них.
     */
    public static CompletableFuture<Boolean> ensureModelReady(ModelSpec spec) {
        String previousOwner = FILE_NAME_OWNER.putIfAbsent(spec.fileName(), spec.id());
        if (previousOwner != null && !previousOwner.equals(spec.id())) {
            throw new IllegalStateException("Коллизия имени файла модели '" + spec.fileName()
                    + "': уже зарегистрирован как '" + previousOwner + "', а сейчас как '" + spec.id() + "'."
                    + " Дай моделям разные fileName.");
        }

        return MODEL_TASKS.computeIfAbsent(spec.id(), id -> {
            Path target = NativeConfig.getModelsDir().resolve(spec.fileName());
            if (isModelReady(spec) && verifyExistingIfPossible(spec, target)) {
                return CompletableFuture.completedFuture(true);
            }
            String taskId = "model:" + id;
            String label = "Загрузка модели " + spec.fileName();
            return CompletableFuture.supplyAsync(() -> runModelDownload(spec, target, taskId, label), AiLibExecutors.DOWNLOAD_EXECUTOR);
        });
    }

    private static boolean runModelDownload(ModelSpec spec, Path target, String taskId, String label) {
        boolean ok = FileDownloader.download(spec.url(), target, spec.sha256(),
                (done, total) -> ProgressBus.publishProgress(taskId, label, done, total));
        ProgressBus.publishFinished(taskId, label, ok);
        return ok;
    }

    /** Если файл уже существует с прошлого запуска и есть ожидаемый sha256 — сверяем, а не слепо доверяем. */
    private static boolean verifyExistingIfPossible(ModelSpec spec, Path target) {
        if (spec.sha256() == null) return true;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[256 * 1024];
            try (var in = Files.newInputStream(target)) {
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            return actual.equalsIgnoreCase(spec.sha256());
        } catch (Exception e) {
            return false;
        }
    }
}
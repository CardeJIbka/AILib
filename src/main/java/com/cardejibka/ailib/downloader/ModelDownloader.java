package com.cardejibka.ailib.downloader;

import com.cardejibka.ailib.AiLibExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class ModelDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-ModelDownloader");

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(NativeConfig.ModelFile model, long downloaded, long total);
    }

    public static CompletableFuture<Boolean> prepareModelsAsync(ProgressListener listener) {
        return CompletableFuture.supplyAsync(() -> prepareModelsBlocking(listener), AiLibExecutors.IO_EXECUTOR);
    }

    public static boolean prepareModels() {
        return prepareModelsBlocking(null);
    }

    public static boolean isModelReady(NativeConfig.ModelFile model) {
        return Files.exists(NativeConfig.getModelsDir().resolve(model.getFileName()));
    }

    private static boolean prepareModelsBlocking(ProgressListener listener) {
        Path modelsDir = NativeConfig.getModelsDir();
        try {
            Files.createDirectories(modelsDir);
        } catch (Exception e) {
            LOGGER.error("Не удалось создать папку моделей {}: {}", modelsDir, e.getMessage());
            return false;
        }

        boolean allOk = true;
        for (NativeConfig.ModelFile model : NativeConfig.ModelFile.values()) {
            Path targetPath = modelsDir.resolve(model.getFileName());
            if (Files.exists(targetPath)) {
                LOGGER.info("Модель {} готова к работе.", model.getFileName());
                continue;
            }

            LOGGER.info("Скачивание модели {}...", model.getFileName());
            boolean ok = downloadFile(model.getDownloadUrl(), targetPath,
                    (downloaded, total) -> {
                        if (listener != null) listener.onProgress(model, downloaded, total);
                    });
            allOk &= ok;
        }
        return allOk;
    }

    @FunctionalInterface
    private interface ByteProgress {
        void update(long downloaded, long total);
    }

    private static boolean downloadFile(String url, Path targetPath, ByteProgress progress) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            long total = -1;
            try {
                HttpRequest headRequest = HttpRequest.newBuilder().uri(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<Void> headResponse = client.send(headRequest, HttpResponse.BodyHandlers.discarding());
                total = headResponse.headers().firstValueAsLong("Content-Length").orElse(-1);
            } catch (Exception ignored) {
            }

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofMinutes(20))
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                LOGGER.error("Сбой скачивания {}. HTTP: {}", targetPath.getFileName(), response.statusCode());
                return false;
            }

            long finalTotal = total;
            long downloadedTotal = 0;

            Path tmpPath = targetPath.resolveSibling(targetPath.getFileName() + ".part");
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloadedTotal += read;
                    if (progress != null) progress.update(downloadedTotal, finalTotal);
                }
            }

            if (finalTotal > 0 && downloadedTotal != finalTotal) {
                LOGGER.error("Файл {} скачан не полностью: {} из {} байт", targetPath.getFileName(), downloadedTotal, finalTotal);
                Files.deleteIfExists(tmpPath);
                return false;
            }

            Files.move(tmpPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.error("Ошибка при закачке модели {}: {}", targetPath.getFileName(), e.getMessage());
            try {
                Files.deleteIfExists(targetPath.resolveSibling(targetPath.getFileName() + ".part"));
            } catch (Exception ignored) {
            }
            return false;
        }
    }
}
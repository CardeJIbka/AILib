package com.cardejibka.ailib.downloader;

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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Один генерик-метод для скачивания и natives-архивов, и файлов моделей —
 * раньше эта логика была продублирована в NativeDownloader и ModelDownloader.
 */
public final class FileDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Download");

    @FunctionalInterface
    public interface ProgressCallback {
        void update(long downloaded, long total);
    }

    private FileDownloader() {
    }

    /**
     * Качает файл во временный "<target>.part", проверяет размер (и sha256, если передан),
     * и только потом атомарно переименовывает в целевой путь — обрыв соединения никогда
     * не оставляет "готовый", но битый файл под финальным именем.
     */
    public static boolean download(String url, Path targetPath, String expectedSha256Hex, ProgressCallback progress) {
        Path tmpPath = targetPath.resolveSibling(targetPath.getFileName() + ".part");
        try {
            Files.createDirectories(targetPath.getParent());

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            long total = -1;
            try {
                HttpRequest head = HttpRequest.newBuilder().uri(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<Void> headResponse = client.send(head, HttpResponse.BodyHandlers.discarding());
                total = headResponse.headers().firstValueAsLong("Content-Length").orElse(-1);
            } catch (Exception ignored) {
                // Не все сервера отвечают на HEAD — просто не покажем total.
            }

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofMinutes(20))
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                LOGGER.error("HTTP {} при скачивании {}", response.statusCode(), url);
                return false;
            }

            MessageDigest digest = expectedSha256Hex != null ? MessageDigest.getInstance("SHA-256") : null;
            long finalTotal = total;
            long downloadedTotal = 0;

            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (digest != null) digest.update(buffer, 0, read);
                    downloadedTotal += read;
                    if (progress != null) progress.update(downloadedTotal, finalTotal);
                }
            }

            if (finalTotal > 0 && downloadedTotal != finalTotal) {
                LOGGER.error("Файл {} скачан не полностью: {} из {} байт", targetPath.getFileName(), downloadedTotal, finalTotal);
                Files.deleteIfExists(tmpPath);
                return false;
            }

            if (digest != null) {
                String actualHex = HexFormat.of().formatHex(digest.digest());
                if (!actualHex.equalsIgnoreCase(expectedSha256Hex)) {
                    LOGGER.error("Несовпадение sha256 для {}: ожидалось {}, получено {}",
                            targetPath.getFileName(), expectedSha256Hex, actualHex);
                    Files.deleteIfExists(tmpPath);
                    return false;
                }
            }

            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.error("Ошибка при скачивании {}: {}", url, e.getMessage());
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Exception ignored) {
            }
            return false;
        }
    }
}
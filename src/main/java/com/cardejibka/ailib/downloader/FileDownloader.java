package com.cardejibka.ailib.downloader;

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
 * Один генерик-метод для скачивания и natives-архивов, и файлов моделей.
 * Качает во временный "<target>.part", проверяет размер и sha256 (если передан),
 * и только потом атомарно переименовывает — обрыв никогда не оставляет битый
 * файл под финальным именем.
 * <p>
 * Только https. Редиректы разрешены, кроме downgrade https -> http. Домен редиректа
 * НЕ сверяется с allow-list намеренно: HuggingFace/GitHub отдают файлы с CDN-доменов
 * вроде cas-bridge.xethub.hf.co, а доверие к ним наследуется от исходного (проверенного) хоста.
 */
public final class FileDownloader {

    @FunctionalInterface
    public interface ProgressCallback {
        void update(long downloaded, long total);
    }

    private FileDownloader() {
    }

    public static void download(String url, Path targetPath, String expectedSha256Hex, ProgressCallback progress)
            throws DownloadException {
        Path tmpPath = targetPath.resolveSibling(targetPath.getFileName() + ".part");
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new DownloadException(DownloadException.Kind.NETWORK, "Разрешён только https: " + url);
            }
            Files.createDirectories(targetPath.getParent());

            try (HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build()) {

                HttpRequest request = HttpRequest.newBuilder().uri(uri)
                        .timeout(Duration.ofMinutes(20))
                        .GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    try (InputStream ignored = response.body()) {
                        // закрываем тело, чтобы освободить соединение
                    }
                    throw new DownloadException(DownloadException.Kind.HTTP_ERROR,
                            "HTTP " + response.statusCode() + " при скачивании " + url);
                }

                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                MessageDigest digest = expectedSha256Hex != null ? MessageDigest.getInstance("SHA-256") : null;
                long downloaded = 0;

                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(tmpPath,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[256 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (digest != null) digest.update(buffer, 0, read);
                        downloaded += read;
                        if (progress != null) progress.update(downloaded, total);
                    }
                }

                if (total > 0 && downloaded != total) {
                    throw new DownloadException(DownloadException.Kind.INCOMPLETE,
                            "Файл " + targetPath.getFileName() + " скачан не полностью: " + downloaded + " из " + total + " байт");
                }

                if (digest != null) {
                    String actualHex = HexFormat.of().formatHex(digest.digest());
                    if (!actualHex.equalsIgnoreCase(expectedSha256Hex)) {
                        throw new DownloadException(DownloadException.Kind.HASH_MISMATCH,
                                "Несовпадение sha256 для " + targetPath.getFileName()
                                        + ": ожидалось " + expectedSha256Hex + ", получено " + actualHex);
                    }
                }

                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (DownloadException e) {
            cleanup(tmpPath);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanup(tmpPath);
            throw new DownloadException(DownloadException.Kind.NETWORK, "Загрузка прервана: " + url, e);
        } catch (Exception e) {
            cleanup(tmpPath);
            throw new DownloadException(DownloadException.Kind.NETWORK,
                    "Ошибка при скачивании " + url + ": " + e.getMessage(), e);
        }
    }

    private static void cleanup(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
        }
    }
}

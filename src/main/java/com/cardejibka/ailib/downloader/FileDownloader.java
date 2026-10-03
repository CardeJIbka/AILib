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
 * One generic method for downloading both native archives and model files.
 * Downloads into a temporary "&lt;target&gt;.part", checks the size and the sha256 (if given) and only
 * then atomically renames it, so an interrupted download never leaves a broken file under the final name.
 * <p>
 * https only. Redirects are followed except https -&gt; http downgrades. The redirect target's domain is
 * deliberately NOT checked against the allow-list: HuggingFace/GitHub serve files from CDN hosts such as
 * cas-bridge.xethub.hf.co, so trust is inherited from the (checked) original host.
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
                throw new DownloadException(DownloadException.Kind.NETWORK, "Only https is allowed: " + url);
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
                        // close the body to release the connection
                    }
                    throw new DownloadException(DownloadException.Kind.HTTP_ERROR,
                            "HTTP " + response.statusCode() + " while downloading " + url);
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
                            "File " + targetPath.getFileName() + " is incomplete: " + downloaded + " of " + total + " bytes");
                }

                if (digest != null) {
                    String actualHex = HexFormat.of().formatHex(digest.digest());
                    if (!actualHex.equalsIgnoreCase(expectedSha256Hex)) {
                        throw new DownloadException(DownloadException.Kind.HASH_MISMATCH,
                                "sha256 mismatch for " + targetPath.getFileName()
                                        + ": expected " + expectedSha256Hex + ", got " + actualHex);
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
            throw new DownloadException(DownloadException.Kind.NETWORK, "Download interrupted: " + url, e);
        } catch (Exception e) {
            cleanup(tmpPath);
            throw new DownloadException(DownloadException.Kind.NETWORK,
                    "Error while downloading " + url + ": " + e.getMessage(), e);
        }
    }

    private static void cleanup(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
        }
    }
}

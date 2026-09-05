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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class NativeDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-NativeDownloader");

    public static void prepareAndLoadAllNatives() {
        Path nativesDir = NativeConfig.getNativesDir();

        try {
            Files.createDirectories(nativesDir);
        } catch (Exception ignored) {}

        for (NativeConfig.AiModule module : NativeConfig.AiModule.values()) {
            Path expectedFile = nativesDir.resolve(module.getExpectedFile());

            if (!Files.exists(expectedFile)) {
                LOGGER.info("Скачивание натива {}...", module.getId());
                Path zipPath = nativesDir.resolve(module.getArchiveName());

                if (downloadFile(module.getDownloadUrl(), zipPath)) {
                    extractZip(zipPath, nativesDir);
                    try {
                        Files.deleteIfExists(zipPath);
                    } catch (Exception ignored) {}
                }
            } else {
                LOGGER.info("Натив {} уже загружен.", module.getId());
            }
        }
    }

    private static boolean downloadFile(String url, Path targetPath) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(targetPath));

            if (response.statusCode() == 200) {
                return true;
            } else {
                Files.deleteIfExists(targetPath);
                LOGGER.error("Сбой скачивания {}. HTTP код: {}", targetPath.getFileName(), response.statusCode());
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Ошибка при скачивании {}: {}", targetPath.getFileName(), e.getMessage());
            return false;
        }
    }

    private static void extractZip(Path zipFile, Path targetDir) {
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // Убираем корневую папку 'piper/' из архива если она есть
                if (entryName.startsWith("piper/")) {
                    entryName = entryName.substring(6);
                }
                if (entryName.isEmpty()) continue;

                Path newPath = targetDir.resolve(entryName).normalize();

                if (!newPath.startsWith(targetDir)) {
                    throw new SecurityException("Zip Slip попытка взлома пути!");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    if (newPath.getParent() != null) {
                        Files.createDirectories(newPath.getParent());
                    }
                    try (OutputStream os = Files.newOutputStream(newPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        zis.transferTo(os);
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            LOGGER.error("Ошибка при распаковке архива {}: {}", zipFile.getFileName(), e.getMessage());
        }
    }
}
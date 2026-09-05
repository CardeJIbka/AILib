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
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class NativeDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-NativeDownloader");

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(NativeConfig.AiModule module, long downloaded, long total);
    }

    public static CompletableFuture<Boolean> prepareAndLoadAllNativesAsync(ProgressListener listener) {
        return CompletableFuture.supplyAsync(() -> {
            boolean allOk = true;
            for (NativeConfig.AiModule module : NativeConfig.AiModule.values()) {
                if (!prepareModule(module, listener)) {
                    allOk = false;
                }
            }
            return allOk;
        }, AiLibExecutors.IO_EXECUTOR);
    }

    public static boolean prepareAndLoadAllNatives() {
        boolean allOk = true;
        for (NativeConfig.AiModule module : NativeConfig.AiModule.values()) {
            if (!prepareModule(module, null)) {
                allOk = false;
            }
        }
        return allOk;
    }

    public static boolean isModuleReady(NativeConfig.AiModule module) {
        try {
            return Files.exists(module.getDir().resolve(module.getExpectedFile()));
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    private static boolean prepareModule(NativeConfig.AiModule module, ProgressListener listener) {
        try {
            Path moduleDir = module.getDir();
            Files.createDirectories(moduleDir);

            Path expectedFile = moduleDir.resolve(module.getExpectedFile());
            if (Files.exists(expectedFile)) {
                LOGGER.info("Натив {} уже загружен.", module.getId());
                return true;
            }

            LOGGER.info("Скачивание натива {}...", module.getId());
            Path zipPath = moduleDir.resolve(module.getArchiveName());

            if (!downloadFile(module.getDownloadUrl(), zipPath, (downloaded, total) -> {
                if (listener != null) listener.onProgress(module, downloaded, total);
            })) {
                return false;
            }

            extractArchive(zipPath, moduleDir, module.isStripRootFolder());

            try {
                Files.deleteIfExists(zipPath);
            } catch (Exception ignored) {
            }

            if (!Files.exists(expectedFile)) {
                LOGGER.error("После распаковки не найден ожидаемый файл {} для модуля {}",
                        module.getExpectedFile(), module.getId());
                return false;
            }
            return true;
        } catch (UnsupportedOperationException e) {
            LOGGER.error("Пропуск модуля {}: {}", module.getId(), e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("Не удалось подготовить модуль {}: {}", module.getId(), e.getMessage());
            return false;
        }
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

            HttpRequest headRequest = HttpRequest.newBuilder().uri(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            long total = -1;
            try {
                HttpResponse<Void> headResponse = client.send(headRequest, HttpResponse.BodyHandlers.discarding());
                total = headResponse.headers().firstValueAsLong("Content-Length").orElse(-1);
            } catch (Exception ignored) {
                // Не все сервера поддерживают HEAD — просто не покажем total.
            }

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                LOGGER.error("Сбой скачивания {}. HTTP код: {}", targetPath.getFileName(), response.statusCode());
                return false;
            }

            long finalTotal = total;
            long downloadedTotal = 0;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloadedTotal += read;
                    if (progress != null) progress.update(downloadedTotal, finalTotal);
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Ошибка при скачивании {}: {}", targetPath.getFileName(), e.getMessage());
            try {
                Files.deleteIfExists(targetPath);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private static void extractArchive(Path archiveFile, Path targetDir, boolean stripRootFolder) throws Exception {
        String name = archiveFile.getFileName().toString().toLowerCase();
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archiveFile, targetDir, stripRootFolder);
        } else {
            extractZip(archiveFile, targetDir, stripRootFolder);
        }
    }

    private static void extractTarGz(Path tarGzFile, Path targetDir, boolean stripRootFolder) throws Exception {
        try (InputStream fis = Files.newInputStream(tarGzFile);
             GZIPInputStream gis = new GZIPInputStream(fis)) {
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(gis, header);
                if (read < 512 || isAllZero(header)) break;

                String entryName = new String(header, 0, 100).trim();
                char typeFlag = (char) header[156];
                long size = Long.parseLong(new String(header, 124, 12).trim().replace("\0", ""), 8);

                if (stripRootFolder && entryName.contains("/")) {
                    entryName = entryName.substring(entryName.indexOf('/') + 1);
                }

                long paddedSize = ((size + 511) / 512) * 512;
                if (entryName.isEmpty() || typeFlag == '5') { // директория или пустое имя после strip
                    skipFully(gis, paddedSize);
                    continue;
                }

                Path newPath = targetDir.resolve(entryName).normalize();
                if (!newPath.startsWith(targetDir)) {
                    throw new SecurityException("Tar Slip попытка взлома пути: " + entryName);
                }
                if (newPath.getParent() != null) {
                    Files.createDirectories(newPath.getParent());
                }

                if (typeFlag == '0' || typeFlag == '\0') { // обычный файл
                    try (OutputStream os = Files.newOutputStream(newPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[64 * 1024];
                        long remaining = size;
                        while (remaining > 0) {
                            int chunk = (int) Math.min(buffer.length, remaining);
                            int n = gis.read(buffer, 0, chunk);
                            if (n < 0) break;
                            os.write(buffer, 0, n);
                            remaining -= n;
                        }
                    }
                    newPath.toFile().setExecutable(true, false);
                    skipFully(gis, paddedSize - size);
                } else {
                    skipFully(gis, paddedSize);
                }
            }
        }
    }

    private static int readFully(InputStream in, byte[] buffer) throws Exception {
        int total = 0;
        while (total < buffer.length) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void skipFully(InputStream in, long n) throws Exception {
        long remaining = n;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, chunk);
            if (read < 0) break;
            remaining -= read;
        }
    }

    private static boolean isAllZero(byte[] buffer) {
        for (byte b : buffer) if (b != 0) return false;
        return true;
    }

    private static void extractZip(Path zipFile, Path targetDir, boolean stripRootFolder) throws Exception {
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');

                if (stripRootFolder && entryName.contains("/")) {
                    entryName = entryName.substring(entryName.indexOf('/') + 1);
                }
                if (entryName.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                Path newPath = targetDir.resolve(entryName).normalize();

                if (!newPath.startsWith(targetDir)) {
                    throw new SecurityException("Zip Slip попытка взлома пути: " + entry.getName());
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
                    // На Linux/macOS архивы часто не сохраняют бит "исполняемый" при распаковке через ZipInputStream.
                    newPath.toFile().setExecutable(true, false);
                }
                zis.closeEntry();
            }
        }
    }
}
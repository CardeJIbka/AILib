package com.cardejibka.ailib.downloader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NativeInstaller {

    private NativeInstaller() {
    }

    public static void extract(Path archiveFile, Path targetDir, boolean stripRootFolder) throws Exception {
        String name = archiveFile.getFileName().toString().toLowerCase();
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archiveFile, targetDir, stripRootFolder);
        } else {
            extractZip(archiveFile, targetDir, stripRootFolder);
        }
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
                    if (newPath.getParent() != null) Files.createDirectories(newPath.getParent());
                    try (OutputStream os = Files.newOutputStream(newPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        zis.transferTo(os);
                    }
                    newPath.toFile().setExecutable(true, false);
                }
                zis.closeEntry();
            }
        }
    }

    /** Минимальный ustar-извлекатель без внешних зависимостей (commons-compress не подключён). */
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
                if (entryName.isEmpty() || typeFlag == '5') {
                    skipFully(gis, paddedSize);
                    continue;
                }

                Path newPath = targetDir.resolve(entryName).normalize();
                if (!newPath.startsWith(targetDir)) {
                    throw new SecurityException("Tar Slip попытка взлома пути: " + entryName);
                }
                if (newPath.getParent() != null) Files.createDirectories(newPath.getParent());

                if (typeFlag == '0' || typeFlag == '\0') {
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
}
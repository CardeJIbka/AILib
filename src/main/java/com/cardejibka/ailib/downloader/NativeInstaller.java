package com.cardejibka.ailib.downloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NativeInstaller {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Installer");

    private NativeInstaller() {
    }

    public static void extract(Path archiveFile, Path targetDir, boolean stripRootFolder) throws Exception {
        Path root = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        String name = archiveFile.getFileName().toString().toLowerCase();
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archiveFile, root, stripRootFolder);
        } else {
            extractZip(archiveFile, root, stripRootFolder);
        }
    }

    private static String stripName(String entryName, boolean stripRootFolder) {
        String n = entryName.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        if (stripRootFolder && n.contains("/")) n = n.substring(n.indexOf('/') + 1);
        return n;
    }

    private static Path safeResolve(Path root, String entryName) {
        Path p = root.resolve(entryName).normalize();
        if (!p.startsWith(root)) {
            throw new SecurityException("Path escapes the install directory: " + entryName);
        }
        return p;
    }

    private static void extractZip(Path zipFile, Path root, boolean stripRootFolder) throws Exception {
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = stripName(entry.getName(), stripRootFolder);
                if (entryName.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                Path newPath = safeResolve(root, entryName);
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

    /** Minimal dependency-free tar extractor: ustar + GNU long names + pax path + symlinks/hardlinks. */
    private static void extractTarGz(Path tarGzFile, Path root, boolean stripRootFolder) throws Exception {
        try (InputStream fis = Files.newInputStream(tarGzFile);
             GZIPInputStream gis = new GZIPInputStream(fis)) {
            byte[] header = new byte[512];
            String pendingLongName = null;

            while (true) {
                int read = readFully(gis, header);
                if (read < 512 || isAllZero(header)) break;

                String name = cString(header, 0, 100);
                if ("ustar".equals(cString(header, 257, 6))) {
                    String prefix = cString(header, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }
                char type = (char) header[156];
                long size = parseOctal(header, 124, 12);
                long padded = ((size + 511) / 512) * 512;

                if (type == 'L') { // GNU long name: the data is the name of the next entry
                    byte[] data = gis.readNBytes((int) size);
                    pendingLongName = new String(data, StandardCharsets.UTF_8).replace("\0", "").trim();
                    skipFully(gis, padded - size);
                    continue;
                }
                if (type == 'x') { // pax header: pick up path=...
                    byte[] data = gis.readNBytes((int) size);
                    String text = new String(data, StandardCharsets.UTF_8);
                    for (String line : text.split("\n")) {
                        int sp = line.indexOf(' ');
                        if (sp > 0 && line.startsWith("path=", sp + 1)) {
                            pendingLongName = line.substring(sp + 1 + "path=".length());
                        }
                    }
                    skipFully(gis, padded - size);
                    continue;
                }
                if (type == 'g') {
                    skipFully(gis, padded);
                    continue;
                }

                if (pendingLongName != null) {
                    name = pendingLongName;
                    pendingLongName = null;
                }
                String entryName = stripName(name, stripRootFolder);
                if (entryName.isEmpty()) {
                    skipFully(gis, padded);
                    continue;
                }
                Path newPath = safeResolve(root, entryName);

                if (type == '5') {
                    Files.createDirectories(newPath);
                    skipFully(gis, padded);
                } else if (type == '2') { // symlink
                    String linkName = cString(header, 157, 100);
                    createSymlink(root, newPath, linkName);
                    skipFully(gis, padded);
                } else if (type == '1') { // hardlink: copy the already extracted file
                    String linkName = stripName(cString(header, 157, 100), stripRootFolder);
                    Path source = safeResolve(root, linkName);
                    if (Files.exists(source)) {
                        if (newPath.getParent() != null) Files.createDirectories(newPath.getParent());
                        Files.copy(source, newPath, StandardCopyOption.REPLACE_EXISTING);
                        newPath.toFile().setExecutable(true, false);
                    }
                    skipFully(gis, padded);
                } else if (type == '0' || type == '\0' || type == '7') {
                    if (newPath.getParent() != null) Files.createDirectories(newPath.getParent());
                    try (OutputStream os = Files.newOutputStream(newPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[64 * 1024];
                        long remaining = size;
                        while (remaining > 0) {
                            int n = gis.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (n < 0) break;
                            os.write(buffer, 0, n);
                            remaining -= n;
                        }
                    }
                    newPath.toFile().setExecutable(true, false);
                    skipFully(gis, padded - size);
                } else {
                    skipFully(gis, padded);
                }
            }
        }
    }

    private static void createSymlink(Path root, Path link, String linkName) {
        try {
            Path resolvedTarget = (link.getParent() != null ? link.getParent() : root).resolve(linkName).normalize();
            if (!resolvedTarget.startsWith(root)) {
                LOGGER.warn("Symlink {} -> {} points outside the install directory, skipped", link, linkName);
                return;
            }
            if (link.getParent() != null) Files.createDirectories(link.getParent());
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, Path.of(linkName));
        } catch (Exception e) {
            LOGGER.warn("Could not create symlink {} -> {}: {}", link, linkName, e.getMessage());
        }
    }

    private static String cString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) end++;
        return new String(buf, off, end - off, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        String s = cString(buf, off, len).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
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
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            remaining -= read;
        }
    }

    private static boolean isAllZero(byte[] buffer) {
        for (byte b : buffer) if (b != 0) return false;
        return true;
    }
}

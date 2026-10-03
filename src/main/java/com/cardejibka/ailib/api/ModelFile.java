package com.cardejibka.ailib.api;

import java.net.URI;
import java.util.Locale;

/**
 * One model file: its name on disk, where to download it from and (optionally) its sha256.
 * Validation lives here so a ModelSpec and its companion files are protected identically.
 */
public record ModelFile(String fileName, String url, String sha256) {

    public ModelFile {
        validateFileName(fileName);
        validateUrl(url);
    }

    public ModelFile(String fileName, String url) {
        this(fileName, url, null);
    }

    static void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("ModelSpec.fileName must not be blank");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (fileName.length() > 128
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..") || fileName.contains(":")
                || fileName.indexOf('\0') >= 0
                || fileName.startsWith(".")
                || lower.endsWith(".part")) {
            throw new IllegalArgumentException("fileName must be a plain file name "
                    + "(no paths, '..', ':', leading dot or '.part' suffix): " + fileName);
        }
    }

    static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("ModelSpec.url must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed url: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("url must be https:// and contain a host: " + url);
        }
    }
}

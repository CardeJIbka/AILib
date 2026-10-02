package com.cardejibka.ailib.api;

import java.net.URI;
import java.util.Locale;

/**
 * Один файл модели: имя на диске + откуда качать + (опционально) sha256.
 * Валидация живёт здесь, поэтому ModelSpec и его companion-файлы защищены одинаково.
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
            throw new IllegalArgumentException("ModelSpec.fileName не может быть пустым");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (fileName.length() > 128
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..") || fileName.contains(":")
                || fileName.indexOf('\0') >= 0
                || fileName.startsWith(".")
                || lower.endsWith(".part")) {
            throw new IllegalArgumentException("fileName должен быть простым именем файла "
                    + "(без путей, '..', ':', ведущей точки и суффикса .part): " + fileName);
        }
    }

    static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("ModelSpec.url не может быть пустым");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный url: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("url должен быть https:// с указанием хоста: " + url);
        }
    }
}

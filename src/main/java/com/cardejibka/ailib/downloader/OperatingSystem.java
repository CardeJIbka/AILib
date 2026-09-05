package com.cardejibka.ailib.downloader;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS("win", ".dll"),
    LINUX("linux", ".so"),
    MACOS("mac", ".dylib");

    private final String id;
    private final String extension;

    OperatingSystem(String id, String extension) {
        this.id = id;
        this.extension = extension;
    }

    public String getId() {
        return id;
    }

    public String getExtension() {
        return extension;
    }

    public static OperatingSystem detect() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) return WINDOWS;
        if (osName.contains("mac") || osName.contains("darwin")) return MACOS;
        if (osName.contains("nux") || osName.contains("nix")) return LINUX;
        throw new UnsupportedOperationException("Неподдерживаемая ОС: " + osName);
    }
}
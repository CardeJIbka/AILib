package com.cardejibka.ailib.downloader;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS("win", ".dll", ".exe"),
    LINUX("linux", ".so", ""),
    MACOS("mac", ".dylib", "");

    private final String id;
    private final String libraryExtension;
    private final String executableSuffix;

    OperatingSystem(String id, String libraryExtension, String executableSuffix) {
        this.id = id;
        this.libraryExtension = libraryExtension;
        this.executableSuffix = executableSuffix;
    }

    public String getId() {
        return id;
    }

    public String getExtension() {
        return libraryExtension;
    }

    public String getExecutableSuffix() {
        return executableSuffix;
    }

    public String exeName(String baseName) {
        return baseName + executableSuffix;
    }

    public static OperatingSystem detect() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) return WINDOWS;
        if (osName.contains("mac") || osName.contains("darwin")) return MACOS;
        if (osName.contains("nux") || osName.contains("nix")) return LINUX;
        throw new UnsupportedOsException(osName);
    }

    public static class UnsupportedOsException extends RuntimeException {
        public UnsupportedOsException(String osName) {
            super("Неподдерживаемая операционная система: " + osName);
        }
    }
}
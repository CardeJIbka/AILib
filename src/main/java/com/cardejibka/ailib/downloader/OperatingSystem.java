package com.cardejibka.ailib.downloader;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS("win", ".exe"),
    LINUX("linux", ""),
    MACOS("mac", "");

    private final String id;
    private final String executableSuffix;

    OperatingSystem(String id, String executableSuffix) {
        this.id = id;
        this.executableSuffix = executableSuffix;
    }

    public String getId() {
        return id;
    }

    public String exeName(String baseName) {
        return baseName + executableSuffix;
    }

    public static OperatingSystem detect() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) return WINDOWS;
        if (osName.contains("mac") || osName.contains("darwin")) return MACOS;
        if (osName.contains("nux") || osName.contains("nix")) return LINUX;
        throw new UnsupportedOperationException("Unsupported operating system: " + osName);
    }
}

package com.cardejibka.ailib.downloader;

import java.util.Locale;

public enum Architecture {
    X64("x86_64"),
    ARM64("arm64");

    private final String id;

    Architecture(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static Architecture detect() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return ARM64;
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) return X64;
        throw new UnsupportedOperationException("Unsupported CPU architecture: " + arch);
    }
}

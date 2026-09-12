package com.cardejibka.ailib.api;

public record ModelSpec(String id, EngineType engine, String fileName, String url, String sha256) {

    public ModelSpec {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("ModelSpec.id не может быть пустым");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("ModelSpec.url не может быть пустым");
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("ModelSpec.fileName не может быть пустым");
    }

    public ModelSpec(String id, EngineType engine, String fileName, String url) {
        this(id, engine, fileName, url, null);
    }
}
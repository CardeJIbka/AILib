package com.cardejibka.ailib.downloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class ModelDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-ModelDownloader");

    public static void prepareModels() {
        Path modelsDir = NativeConfig.getModelsDir();
        try {
            Files.createDirectories(modelsDir);
        } catch (Exception ignored) {}

        for (NativeConfig.ModelFile model : NativeConfig.ModelFile.values()) {
            Path targetPath = modelsDir.resolve(model.getFileName());
            if (!Files.exists(targetPath)) {
                LOGGER.info("Скачивание легкой модели {}...", model.getFileName());
                downloadFile(model.getDownloadUrl(), targetPath);
            } else {
                LOGGER.info("Модель {} готова к работе.", model.getFileName());
            }
        }
    }

    private static void downloadFile(String url, Path targetPath) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(targetPath));

            if (response.statusCode() != 200) {
                Files.deleteIfExists(targetPath);
                LOGGER.error("Сбой скачивания {}. HTTP: {}", targetPath.getFileName(), response.statusCode());
            }
        } catch (Exception e) {
            LOGGER.error("Ошибка при закачке модели {}: {}", targetPath.getFileName(), e.getMessage());
        }
    }
}
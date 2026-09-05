package com.cardejibka.ailib.engine;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.downloader.NativeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PiperEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-TTS");

    public static boolean isReady() {
        Path piperDir = NativeConfig.AiModule.TTS.getDir();
        Path piperExe = piperDir.resolve(NativeConfig.AiModule.TTS.getExpectedFile());
        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.TTS_MODEL.getFileName());
        return Files.exists(piperExe) && Files.exists(modelPath);
    }

    public static byte[] synthesize(String text) {
        Path piperDir = NativeConfig.AiModule.TTS.getDir();
        Path piperExe = piperDir.resolve(NativeConfig.AiModule.TTS.getExpectedFile());

        if (!Files.exists(piperExe)) {
            LOGGER.error("[TTS Error] Бинарник piper не найден в {}", piperExe.toAbsolutePath());
            return new byte[0];
        }

        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.TTS_MODEL.getFileName());
        Path configPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.TTS_CONFIG.getFileName());
        // Уникальное имя, чтобы параллельные (пусть и сериализованные семафором) вызовы
        // не затирали файл друг друга и не путали результаты между собой.
        Path outputFile = piperDir.resolve("temp_tts_" + UUID.randomUUID() + ".wav");
        Path espeakDir = piperDir.resolve("espeak-ng-data");
        Path tashkeelModel = piperDir.resolve("libtashkeel_model.ort");

        boolean acquired;
        try {
            acquired = AiLibExecutors.TTS_SLOT.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        }
        if (!acquired) {
            LOGGER.warn("[TTS] Другой синтез уже выполняется, запрос отклонён.");
            return new byte[0];
        }

        try {
            LOGGER.info("[TTS] Генерация речи для: \"{}\"", text);

            List<String> command = new ArrayList<>();
            command.add(piperExe.toAbsolutePath().toString());
            command.add("--model");
            command.add(relativeOrAbsolute(piperDir, modelPath));
            command.add("--config");
            command.add(relativeOrAbsolute(piperDir, configPath));
            command.add("--output_file");
            command.add(relativeOrAbsolute(piperDir, outputFile));
            if (Files.exists(espeakDir)) {
                command.add("--espeak_data");
                command.add(relativeOrAbsolute(piperDir, espeakDir));
            }
            if (Files.exists(tashkeelModel)) {
                command.add("--tashkeel_model");
                command.add(relativeOrAbsolute(piperDir, tashkeelModel));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(piperDir.toFile());

            Process process = pb.start();

            try (OutputStream os = process.getOutputStream();
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                writer.write(text);
                writer.newLine();
                writer.flush();
            }

            StringBuilder stderr = new StringBuilder();
            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {
                }
            }, "AiLib-TTS-stderr");
            errThread.start();

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            errThread.join();

            if (!finished) {
                process.destroyForcibly();
                LOGGER.error("[TTS Timeout] Piper завис.");
                return new byte[0];
            }

            if (process.exitValue() != 0 || !Files.exists(outputFile)) {
                LOGGER.error("[TTS Failure] Код ошибки: {}. Stderr:\n{}", process.exitValue(), stderr);
                return new byte[0];
            }

            byte[] bytes = Files.readAllBytes(outputFile);
            LOGGER.info("[TTS Success] Синтезировано {} байт.", bytes.length);
            return bytes;

        } catch (Exception e) {
            LOGGER.error("[TTS Critical Error]", e);
            return new byte[0];
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (Exception ignored) {
            }
            AiLibExecutors.TTS_SLOT.release();
        }
    }

    private static String relativeOrAbsolute(Path base, Path target) {
        try {
            return base.toAbsolutePath().relativize(target.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return target.toAbsolutePath().toString();
        }
    }
}
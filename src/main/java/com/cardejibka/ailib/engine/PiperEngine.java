package com.cardejibka.ailib.engine;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.api.AiLibException;
import com.cardejibka.ailib.api.TtsEngine;
import com.cardejibka.ailib.config.AiLibConfig;
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

public class PiperEngine implements TtsEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-TTS");

    @Override
    public boolean isNativeReady() {
        Path piperDir = NativeConfig.AiModule.TTS.getDir();
        return Files.exists(piperDir.resolve(NativeConfig.AiModule.TTS.getExpectedFile()));
    }

    @Override
    public byte[] synthesize(String text, Path voiceModelPath) {
        Path piperDir = NativeConfig.AiModule.TTS.getDir();
        Path piperExe = piperDir.resolve(NativeConfig.AiModule.TTS.getExpectedFile());

        if (!Files.exists(piperExe)) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "Бинарник piper не найден");
        }
        if (!Files.exists(voiceModelPath)) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "Модель голоса не найдена: " + voiceModelPath);
        }

        Path configPath = voiceModelPath.resolveSibling(voiceModelPath.getFileName() + ".json");
        Path outputFile = piperDir.resolve("temp_tts_" + UUID.randomUUID() + ".wav");
        Path espeakDir = piperDir.resolve("espeak-ng-data");
        Path tashkeelModel = piperDir.resolve("libtashkeel_model.ort");

        boolean acquired;
        try {
            acquired = AiLibExecutors.TTS_SLOT.tryAcquire(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.BUSY, "Ожидание очереди было прервано");
        }
        if (!acquired) {
            throw new AiLibException(AiLibException.Reason.BUSY, "TTS занята другим запросом, попробуйте позже");
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(piperExe.toAbsolutePath().toString());
            command.add("--model");
            command.add(relativeOrAbsolute(piperDir, voiceModelPath));
            if (Files.exists(configPath)) {
                command.add("--config");
                command.add(relativeOrAbsolute(piperDir, configPath));
            }
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
                    while ((line = reader.readLine()) != null) stderr.append(line).append("\n");
                } catch (Exception ignored) {
                }
            }, "AiLib-TTS-stderr");
            errThread.start();

            boolean finished = process.waitFor(AiLibConfig.get().ttsTimeoutSeconds, TimeUnit.SECONDS);
            errThread.join();

            if (!finished) {
                process.destroyForcibly();
                throw new AiLibException(AiLibException.Reason.TIMEOUT, "Piper завис");
            }
            if (process.exitValue() != 0 || !Files.exists(outputFile)) {
                throw new AiLibException(AiLibException.Reason.PROCESS_FAILED,
                        "Piper завершился с кодом " + process.exitValue() + ": " + stderr);
            }

            return Files.readAllBytes(outputFile);

        } catch (AiLibException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("[TTS Critical Error]", e);
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "Исключение TTS: " + e.getMessage());
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
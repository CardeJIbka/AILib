package com.cardejibka.ailib.engine;

import com.cardejibka.ailib.downloader.NativeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PiperEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-TTS");

    public static byte[] synthesize(String text) {
        Path piperDir = NativeConfig.AiModule.TTS.getDir();
        Path piperExe = piperDir.resolve("piper.exe");

        if (!Files.exists(piperExe)) {
            LOGGER.error("[TTS Error] piper.exe не найден в {}", piperDir.toAbsolutePath());
            return new byte[0];
        }

        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.TTS_MODEL.getFileName());
        Path configPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.TTS_CONFIG.getFileName());
        Path outputFile = piperDir.resolve("temp_tts.wav");
        Path espeakDir = piperDir.resolve("espeak-ng-data");
        Path tashkeelModel = piperDir.resolve("libtashkeel_model.ort");

        try {
            Files.deleteIfExists(outputFile);
        } catch (Exception ignored) {}

        LOGGER.info("[TTS] Генерация речи для: \"{}\"", text);

        List<String> command = new ArrayList<>();

        // Сам бинарник можно передать абсолютным путем, ОС запустит его корректно
        command.add(piperExe.toAbsolutePath().toString());

        // А вот аргументы передаем ОТНОСИТЕЛЬНО рабочей папки piperDir
        command.add("--model");
        command.add(piperDir.relativize(modelPath).toString()); // Получится ..\ai_models\имя.onnx

        command.add("--config");
        command.add(piperDir.relativize(configPath).toString());

        command.add("--output_file");
        command.add(piperDir.relativize(outputFile).toString()); // Получится просто temp_tts.wav

        command.add("--espeak_data");
        command.add(piperDir.relativize(espeakDir).toString()); // Получится просто espeak-ng-data

        if (Files.exists(tashkeelModel)) {
            command.add("--tashkeel_model");
            command.add(piperDir.relativize(tashkeelModel).toString());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        // Фиксируем рабочую директорию, чтобы относительные пути сработали
        pb.directory(piperDir.toFile());

        try {
            Process process = pb.start();

            // Передача текста через UTF-8
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
                } catch (Exception ignored) {}
            });
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
            Files.deleteIfExists(outputFile);
            LOGGER.info("[TTS Success] Синтезировано {} байт.", bytes.length);
            return bytes;

        } catch (Exception e) {
            LOGGER.error("[TTS Critical Error]", e);
            return new byte[0];
        }
    }
}
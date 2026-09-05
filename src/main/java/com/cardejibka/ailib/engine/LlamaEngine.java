package com.cardejibka.ailib.engine;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.downloader.NativeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LlamaEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-LLM");
    private static final String RESPONSE_MARKER = "###RESPONSE###";

    public static boolean isReady() {
        Path llamaDir = NativeConfig.AiModule.LLM.getDir();
        Path llamaCli = llamaDir.resolve(NativeConfig.AiModule.LLM.getExpectedFile());
        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.LLM_MODEL.getFileName());
        return Files.exists(llamaCli) && Files.exists(modelPath);
    }

    public static String generate(String prompt) {
        Path llamaDir = NativeConfig.AiModule.LLM.getDir();
        Path llamaCli = llamaDir.resolve(NativeConfig.AiModule.LLM.getExpectedFile());
        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.LLM_MODEL.getFileName());

        if (!Files.exists(llamaCli) || !Files.exists(modelPath)) {
            LOGGER.error("[LLM Error] Бинарник или модель Llama не найдены!");
            return "Ошибка: Модель или llama-cli отсутствуют.";
        }

        boolean acquired;
        try {
            acquired = AiLibExecutors.LLM_SLOT.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Ошибка: ожидание своей очереди было прервано.";
        }
        if (!acquired) {
            return "Сейчас уже выполняется другой запрос к LLM, попробуйте чуть позже.";
        }

        try {
            LOGGER.info("[LLM] Генерация ответа на промпт: \"{}\"", prompt);

            String fullPrompt = "<|start_header_id|>system<|end_header_id|>\n" +
                    "Ты полезный ассистент. Отвечай кратко, четко и на русском языке.<|eot_id|>\n" +
                    "<|start_header_id|>user<|end_header_id|>\n" +
                    prompt +
                    "<|eot_id|>\n" +
                    "<|start_header_id|>assistant<|end_header_id|>\n" +
                    RESPONSE_MARKER;

            List<String> command = new ArrayList<>();
            command.add(llamaCli.toAbsolutePath().toString());
            command.add("-m");
            command.add(relativeOrAbsolute(llamaCli.getParent(), modelPath));
            command.add("--log-disable");
            command.add("-p");
            command.add(fullPrompt);
            command.add("-n");
            command.add("128");
            command.add("-c");
            command.add("2048");
            command.add("--temp");
            command.add("0.6");

            ProcessBuilder pb = new ProcessBuilder(command);
            // Рабочая директория = папка самого бинарника (на Linux llama-cli лежит в
            // build/bin/ вместе с .so-библиотеками, а не в корне модуля).
            pb.directory(llamaCli.getParent().toFile());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            StringBuilder outputBuffer = new StringBuilder();

            Process process = pb.start();
            process.getOutputStream().close();

            CompletableFuture<Void> readTask = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputBuffer.append(line).append("\n");
                    }
                } catch (Exception e) {
                    LOGGER.error("[LLM Error] Ошибка чтения потока llama-cli", e);
                }
            }, AiLibExecutors.IO_EXECUTOR);

            boolean completed = process.waitFor(35, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "Ошибка: Превышено время ожидания ответа AI.";
            }

            readTask.join();
            String cleanAnswer = extractAnswer(outputBuffer.toString());
            return cleanAnswer.isEmpty() ? "LLM завершилась без ответа." : cleanAnswer;

        } catch (Exception e) {
            LOGGER.error("[LLM Exception]", e);
            return "Исключение LLM: " + e.getMessage();
        } finally {
            AiLibExecutors.LLM_SLOT.release();
        }
    }

    private static String relativeOrAbsolute(Path base, Path target) {
        try {
            return base.toAbsolutePath().relativize(target.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return target.toAbsolutePath().toString();
        }
    }

    private static String extractAnswer(String rawOutput) {
        String clean = rawOutput.replaceAll("\u001B\\[[;\\d]*[ -/]*[@-~]", "");
        if (!clean.contains(RESPONSE_MARKER)) return clean.trim();

        String answerPart = clean.substring(clean.indexOf(RESPONSE_MARKER) + RESPONSE_MARKER.length());

        if (answerPart.contains("<|eot_id|>")) answerPart = answerPart.substring(0, answerPart.indexOf("<|eot_id|>"));
        if (answerPart.contains("<|im_end|>")) answerPart = answerPart.substring(0, answerPart.indexOf("<|im_end|>"));
        if (answerPart.contains("[ Prompt:")) answerPart = answerPart.substring(0, answerPart.indexOf("[ Prompt:"));

        return answerPart.replaceAll("\r", "").trim().replaceAll("^#+|#+$", "").trim();
    }
}
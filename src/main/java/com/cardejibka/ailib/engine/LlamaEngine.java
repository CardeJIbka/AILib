package com.cardejibka.ailib.engine;

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

    public static String generate(String prompt) {
        Path llamaDir = NativeConfig.AiModule.LLM.getDir(); // Папка ai_natives
        Path llamaCli = llamaDir.resolve("llama-cli.exe");
        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.LLM_MODEL.getFileName());

        if (!Files.exists(llamaCli) || !Files.exists(modelPath)) {
            LOGGER.error("[LLM Error] Бинарник или модель Llama не найдены!");
            return "Ошибка: Модель или llama-cli отсутствуют.";
        }

        LOGGER.info("[LLM] Генерация ответа на промпт: \"{}\"", prompt);

        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("<|start_header_id|>system<|end_header_id|>\n")
                .append("Ты полезный ассистент. Отвечай кратко, четко и на русском языке.<|eot_id|>\n")
                .append("<|start_header_id|>user<|end_header_id|>\n")
                .append(prompt)
                .append("<|eot_id|>\n")
                .append("<|start_header_id|>assistant<|end_header_id|>\n")
                .append(RESPONSE_MARKER);

        List<String> command = new ArrayList<>();
        command.add(llamaCli.toAbsolutePath().toString());

        // Исправление кириллицы: передаем путь к модели относительно ai_natives
        command.add("-m");
        command.add(llamaDir.toAbsolutePath().relativize(modelPath.toAbsolutePath()).toString());

        // Отключаем лишние логи llama.cpp, чтобы упростить парсинг ответа
        command.add("--log-disable");

        command.add("-p");
        command.add(fullPrompt.toString());
        command.add("-n");
        command.add("128");
        command.add("-c");
        command.add("2048");
        command.add("--temp");
        command.add("0.6");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(llamaDir.toFile());

        // Перенаправляем вывод ошибок (stderr со статистикой llama.cpp) в "мусорную корзину",
        // чтобы он не смешивался с чистым текстом ответа из stdout
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        StringBuilder outputBuffer = new StringBuilder();

        try {
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
            });

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
        }
    }

    private static String extractAnswer(String rawOutput) {
        // Удаляем ANSI-escape последовательности (цветовые коды консоли)
        String clean = rawOutput.replaceAll("\u001B\\[[;\\d]*[ -/]*[@-~]", "");
        if (!clean.contains(RESPONSE_MARKER)) return clean.trim(); // Если маркера нет, отдаем что есть

        String answerPart = clean.substring(clean.indexOf(RESPONSE_MARKER) + RESPONSE_MARKER.length());

        // Обрезаем служебные теги
        if (answerPart.contains("<|eot_id|>")) answerPart = answerPart.substring(0, answerPart.indexOf("<|eot_id|>"));
        if (answerPart.contains("<|im_end|>")) answerPart = answerPart.substring(0, answerPart.indexOf("<|im_end|>"));

        // Если статистика логирования всё-таки попала в текст, отсекаем её
        if (answerPart.contains("[ Prompt:")) {
            answerPart = answerPart.substring(0, answerPart.indexOf("[ Prompt:"));
        }

        // Очищаем символ возврата каретки, пробелы по краям и лишние символы '#'
        return answerPart.replaceAll("\r", "").trim().replaceAll("^#+|#+$", "").trim();
    }
}
package com.cardejibka.ailib.engine;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.api.AiLibException;
import com.cardejibka.ailib.api.LlmEngine;
import com.cardejibka.ailib.api.LlmRequest;
import com.cardejibka.ailib.api.PromptFormat;
import com.cardejibka.ailib.config.AiLibConfig;
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

public class LlamaEngine implements LlmEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-LLM");
    private static final String RESPONSE_MARKER = "###RESPONSE###";

    @Override
    public boolean isNativeReady() {
        return NativeConfig.AiModule.LLM.resolveExecutable() != null;
    }

    @Override
    public String generate(LlmRequest request, Path modelPath) {
        Path llamaCli = NativeConfig.AiModule.LLM.resolveExecutable();
        if (llamaCli == null || !Files.exists(modelPath)) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "The llama binary or the model was not found");
        }

        boolean acquired;
        try {
            acquired = AiLibExecutors.LLM_SLOT.tryAcquire(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.BUSY, "Waiting in the queue was interrupted");
        }
        if (!acquired) {
            throw new AiLibException(AiLibException.Reason.BUSY, "The LLM is busy with another request, try again later");
        }

        try {
            AiLibConfig cfg = AiLibConfig.get();
            String system = request.systemPrompt() != null ? request.systemPrompt() : cfg.llmSystemPrompt;
            int maxTokens = request.maxTokens() != null ? request.maxTokens() : cfg.llmMaxTokens;
            double temperature = request.temperature() != null ? request.temperature() : cfg.llmTemperature;
            PromptFormat format = request.format() != null ? request.format() : PromptFormat.LLAMA3;

            LOGGER.debug("[LLM] Generating a reply ({} prompt chars, format {})", request.prompt().length(), format);

            // Strip the marker from user text so it cannot fake the answer boundary.
            String userPrompt = request.prompt().replace(RESPONSE_MARKER, "");
            String fullPrompt = format.build(system, userPrompt) + RESPONSE_MARKER;

            List<String> command = new ArrayList<>();
            command.add(llamaCli.toAbsolutePath().toString());
            command.add("-m");
            command.add(ProcessUtil.relativeOrAbsolute(llamaCli.getParent(), modelPath));
            command.add("--log-disable");
            command.add("-p");
            command.add(fullPrompt);
            command.add("-n");
            command.add(String.valueOf(maxTokens));
            command.add("-c");
            command.add(String.valueOf(cfg.llmContextSize));
            command.add("--temp");
            command.add(String.valueOf(temperature));
            command.addAll(cfg.llmExtraArgs);

            ProcessBuilder pb = new ProcessBuilder(command);
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
                    LOGGER.error("Error reading llama-cli output", e);
                }
            }, AiLibExecutors.PROCESS_IO_EXECUTOR);

            boolean completed = process.waitFor(cfg.llmTimeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                ProcessUtil.killAndWait(process);
                readTask.cancel(true);
                throw new AiLibException(AiLibException.Reason.TIMEOUT, "The LLM did not answer in time");
            }

            readTask.join();
            String cleanAnswer = extractAnswer(outputBuffer.toString());
            if (cleanAnswer.isEmpty()) {
                throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "The LLM finished without an answer");
            }
            return cleanAnswer;

        } catch (AiLibException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "Generation was interrupted");
        } catch (Exception e) {
            LOGGER.error("[LLM Exception]", e);
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "LLM error: " + e.getMessage());
        } finally {
            AiLibExecutors.LLM_SLOT.release();
        }
    }

    static String extractAnswer(String rawOutput) {
        String clean = rawOutput.replaceAll("\u001B\\[[;\\d]*[ -/]*[@-~]", "").replace("\r", "");
        // The marker is the last thing in the prompt, so the answer follows its LAST occurrence.
        int markerIdx = clean.lastIndexOf(RESPONSE_MARKER);
        String answer = markerIdx < 0 ? clean : clean.substring(markerIdx + RESPONSE_MARKER.length());
        int cut = answer.length();
        for (String stop : PromptFormat.STOP_MARKERS) {
            int idx = answer.indexOf(stop);
            if (idx >= 0 && idx < cut) cut = idx;
        }
        return answer.substring(0, cut).trim();
    }
}

package com.cardejibka.ailib.engine;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.api.AiLibException;
import com.cardejibka.ailib.api.TtsEngine;
import com.cardejibka.ailib.config.AiLibConfig;
import com.cardejibka.ailib.downloader.NativeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
        return NativeConfig.AiModule.TTS.resolveExecutable() != null;
    }

    @Override
    public byte[] synthesize(String text, Path voiceModelPath) {
        Path piperExe = NativeConfig.AiModule.TTS.resolveExecutable();
        if (piperExe == null) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "The piper binary was not found");
        }
        if (!Files.exists(voiceModelPath)) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "Voice model not found: " + voiceModelPath);
        }

        Path piperDir = piperExe.getParent();
        Path configPath = voiceModelPath.resolveSibling(voiceModelPath.getFileName() + ".json");
        Path outputFile = piperDir.resolve("temp_tts_" + UUID.randomUUID() + ".wav");
        Path espeakDir = piperDir.resolve("espeak-ng-data");
        Path tashkeelModel = piperDir.resolve("libtashkeel_model.ort");

        boolean acquired;
        try {
            acquired = AiLibExecutors.TTS_SLOT.tryAcquire(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.BUSY, "Waiting in the queue was interrupted");
        }
        if (!acquired) {
            throw new AiLibException(AiLibException.Reason.BUSY, "TTS is busy with another request, try again later");
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(piperExe.toAbsolutePath().toString());
            command.add("--model");
            command.add(ProcessUtil.relativeOrAbsolute(piperDir, voiceModelPath));
            if (Files.exists(configPath)) {
                command.add("--config");
                command.add(ProcessUtil.relativeOrAbsolute(piperDir, configPath));
            }
            command.add("--output_file");
            command.add(ProcessUtil.relativeOrAbsolute(piperDir, outputFile));
            if (Files.exists(espeakDir)) {
                command.add("--espeak_data");
                command.add(ProcessUtil.relativeOrAbsolute(piperDir, espeakDir));
            }
            if (Files.exists(tashkeelModel)) {
                command.add("--tashkeel_model");
                command.add(ProcessUtil.relativeOrAbsolute(piperDir, tashkeelModel));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(piperDir.toFile());
            Process process = pb.start();

            StringBuffer stderr = new StringBuffer();
            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (stderr.length() < 4000) stderr.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                }
            }, "AiLib-TTS-stderr");
            errThread.setDaemon(true);
            errThread.start();

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(text);
                writer.newLine();
                writer.flush();
            } catch (IOException ignored) {
                // The process may have died early; the exit code and stderr will tell why.
            }

            boolean finished = process.waitFor(AiLibConfig.get().ttsTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                // Kill first, join second: otherwise join waits for a process nobody is killing.
                ProcessUtil.killAndWait(process);
                errThread.join(1000);
                throw new AiLibException(AiLibException.Reason.TIMEOUT, "Piper timed out");
            }
            errThread.join(2000);

            if (process.exitValue() != 0 || !Files.exists(outputFile)) {
                throw new AiLibException(AiLibException.Reason.PROCESS_FAILED,
                        "Piper exited with code " + process.exitValue() + ": " + stderr);
            }
            return Files.readAllBytes(outputFile);

        } catch (AiLibException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "Synthesis was interrupted");
        } catch (Exception e) {
            LOGGER.error("[TTS Critical Error]", e);
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "TTS error: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (Exception ignored) {
            }
            AiLibExecutors.TTS_SLOT.release();
        }
    }
}

package com.cardejibka.ailib.engine;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.api.AiLibException;
import com.cardejibka.ailib.api.SttEngine;
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

public class WhisperEngine implements SttEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-STT");

    @Override
    public boolean isNativeReady() {
        return NativeConfig.AiModule.STT.resolveExecutable() != null;
    }

    @Override
    public String transcribe(Path wavAudioPath, Path modelPath) {
        Path whisperCli = NativeConfig.AiModule.STT.resolveExecutable();
        if (whisperCli == null) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "The whisper-cli binary was not found");
        }
        if (!Files.exists(wavAudioPath)) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "WAV file not found: " + wavAudioPath);
        }
        if (!Files.exists(modelPath)) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "Whisper model not found: " + modelPath);
        }

        boolean acquired;
        try {
            acquired = AiLibExecutors.STT_SLOT.tryAcquire(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.BUSY, "Waiting in the queue was interrupted");
        }
        if (!acquired) {
            throw new AiLibException(AiLibException.Reason.BUSY, "STT is busy with another request, try again later");
        }

        Path workingDir = whisperCli.getParent();
        try {
            AiLibConfig cfg = AiLibConfig.get();
            List<String> command = new ArrayList<>();
            command.add(whisperCli.toAbsolutePath().toString());
            command.add("-m");
            command.add(ProcessUtil.relativeOrAbsolute(workingDir, modelPath));
            command.add("-f");
            command.add(ProcessUtil.relativeOrAbsolute(workingDir, wavAudioPath));
            command.add("-l");
            command.add(cfg.sttLanguage);
            command.add("-t");
            command.add(String.valueOf(cfg.sttThreads));
            command.add("-nt");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            // whisper-cli logs a lot to stderr; without reading/redirecting it the pipe fills up and the process stalls.
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            process.getOutputStream().close();

            StringBuilder stdout = new StringBuilder();
            // Read stdout asynchronously, otherwise the timeout cannot fire: the caller would hang in readLine().
            CompletableFuture<Void> readTask = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) stdout.append(line).append(' ');
                } catch (Exception e) {
                    LOGGER.error("Error reading whisper-cli output", e);
                }
            }, AiLibExecutors.PROCESS_IO_EXECUTOR);

            boolean finished = process.waitFor(cfg.sttTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                ProcessUtil.killAndWait(process);
                readTask.cancel(true);
                throw new AiLibException(AiLibException.Reason.TIMEOUT, "Transcription timed out");
            }
            readTask.join();

            if (process.exitValue() != 0) {
                throw new AiLibException(AiLibException.Reason.PROCESS_FAILED,
                        "whisper-cli exited with code " + process.exitValue());
            }
            return stdout.toString().trim();
        } catch (AiLibException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "Transcription was interrupted");
        } catch (Exception e) {
            LOGGER.error("[STT Error]", e);
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "STT error: " + e.getMessage());
        } finally {
            AiLibExecutors.STT_SLOT.release();
        }
    }
}

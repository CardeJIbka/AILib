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
            throw new AiLibException(AiLibException.Reason.NOT_READY, "Бинарник whisper-cli не найден");
        }
        if (!Files.exists(wavAudioPath)) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "Файл WAV не найден: " + wavAudioPath);
        }
        if (!Files.exists(modelPath)) {
            throw new AiLibException(AiLibException.Reason.NOT_READY, "Модель Whisper не найдена: " + modelPath);
        }

        boolean acquired;
        try {
            acquired = AiLibExecutors.STT_SLOT.tryAcquire(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.BUSY, "Ожидание очереди было прервано");
        }
        if (!acquired) {
            throw new AiLibException(AiLibException.Reason.BUSY, "STT занята другим запросом, попробуйте позже");
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
            // whisper-cli пишет много логов в stderr — без чтения/перенаправления pipe заполнится и процесс встанет.
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            process.getOutputStream().close();

            StringBuilder stdout = new StringBuilder();
            // Чтение stdout — асинхронно, иначе таймаут не сработает: основной поток завис бы в readLine().
            CompletableFuture<Void> readTask = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) stdout.append(line).append(' ');
                } catch (Exception e) {
                    LOGGER.error("Ошибка чтения потока whisper-cli", e);
                }
            }, AiLibExecutors.PROCESS_IO_EXECUTOR);

            boolean finished = process.waitFor(cfg.sttTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                ProcessUtil.killAndWait(process);
                readTask.cancel(true);
                throw new AiLibException(AiLibException.Reason.TIMEOUT, "Превышено время ожидания распознавания");
            }
            readTask.join();

            if (process.exitValue() != 0) {
                throw new AiLibException(AiLibException.Reason.PROCESS_FAILED,
                        "whisper-cli завершился с кодом " + process.exitValue());
            }
            return stdout.toString().trim();
        } catch (AiLibException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "Распознавание прервано");
        } catch (Exception e) {
            LOGGER.error("[STT Error]", e);
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "Исключение STT: " + e.getMessage());
        } finally {
            AiLibExecutors.STT_SLOT.release();
        }
    }
}

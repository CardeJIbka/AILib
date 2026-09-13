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
import java.util.concurrent.TimeUnit;

public class WhisperEngine implements SttEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-STT");

    @Override
    public boolean isNativeReady() {
        Path exe = resolveExecutable();
        return exe != null && Files.exists(exe);
    }

    @Override
    public String transcribe(Path wavAudioPath, Path modelPath) {
        Path whisperCli = resolveExecutable();
        if (whisperCli == null || !Files.exists(whisperCli)) {
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
            command.add(relativeOrAbsolute(workingDir, modelPath));
            command.add("-f");
            command.add(relativeOrAbsolute(workingDir, wavAudioPath));
            command.add("-l");
            command.add(cfg.sttLanguage);
            command.add("-t");
            command.add(String.valueOf(cfg.sttThreads));
            command.add("-nt");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) stdout.append(line).append(" ");
            }

            boolean finished = process.waitFor(cfg.sttTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AiLibException(AiLibException.Reason.TIMEOUT, "Превышено время ожидания распознавания");
            }

            return stdout.toString().trim();
        } catch (AiLibException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("[STT Error]", e);
            throw new AiLibException(AiLibException.Reason.PROCESS_FAILED, "Исключение STT: " + e.getMessage());
        } finally {
            AiLibExecutors.STT_SLOT.release();
        }
    }

    private static Path resolveExecutable() {
        Path dir = NativeConfig.AiModule.STT.getDir();
        Path expected = dir.resolve(NativeConfig.AiModule.STT.getExpectedFile());
        if (Files.exists(expected)) return expected;

        Path fallback1 = dir.resolve("Release").resolve("whisper-cli.exe");
        if (Files.exists(fallback1)) return fallback1;
        Path fallback2 = dir.resolve("main.exe");
        if (Files.exists(fallback2)) return fallback2;
        Path fallback3 = dir.resolve("whisper-cli");
        if (Files.exists(fallback3)) return fallback3;

        return expected;
    }

    private static String relativeOrAbsolute(Path base, Path target) {
        try {
            return base.toAbsolutePath().relativize(target.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return target.toAbsolutePath().toString();
        }
    }
}
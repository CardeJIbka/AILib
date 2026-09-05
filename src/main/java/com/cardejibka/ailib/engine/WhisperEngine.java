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
import java.util.concurrent.TimeUnit;

public class WhisperEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-STT");

    public static boolean isReady() {
        Path whisperCli = resolveExecutable();
        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.STT_MODEL.getFileName());
        return whisperCli != null && Files.exists(whisperCli) && Files.exists(modelPath);
    }

    public static String transcribe(Path wavAudioPath) {
        Path whisperCli = resolveExecutable();

        if (whisperCli == null || !Files.exists(whisperCli)) {
            return "Ошибка: бинарник whisper-cli не найден!";
        }

        Path workingDir = whisperCli.getParent();
        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.STT_MODEL.getFileName());
        if (!Files.exists(wavAudioPath)) {
            return "Ошибка: файл WAV не найден: " + wavAudioPath;
        }

        boolean acquired;
        try {
            acquired = AiLibExecutors.STT_SLOT.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Ошибка: ожидание своей очереди было прервано.";
        }
        if (!acquired) {
            return "Сейчас уже выполняется другое распознавание, попробуйте чуть позже.";
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(whisperCli.toAbsolutePath().toString());
            command.add("-m");
            command.add(relativeOrAbsolute(workingDir, modelPath));
            command.add("-f");
            command.add(relativeOrAbsolute(workingDir, wavAudioPath));
            command.add("-l");
            command.add("auto");
            command.add("-t");
            command.add("2");
            command.add("-nt");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());

            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append(" ");
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Ошибка: Превышено время ожидания распознавания.";
            }

            return stdout.toString().trim();
        } catch (Exception e) {
            LOGGER.error("[STT Error]", e);
            return "Ошибка STT: " + e.getMessage();
        } finally {
            AiLibExecutors.STT_SLOT.release();
        }
    }

    private static Path resolveExecutable() {
        Path dir = NativeConfig.AiModule.STT.getDir();
        Path expected = dir.resolve(NativeConfig.AiModule.STT.getExpectedFile());
        if (Files.exists(expected)) return expected;

        // Резервные варианты для старых сборок / отличающейся структуры архива.
        Path fallback1 = dir.resolve("Release").resolve("whisper-cli.exe");
        if (Files.exists(fallback1)) return fallback1;
        Path fallback2 = dir.resolve("main.exe");
        if (Files.exists(fallback2)) return fallback2;
        Path fallback3 = dir.resolve("whisper-cli");
        if (Files.exists(fallback3)) return fallback3;

        return expected; // вернём ожидаемый путь, даже если файла нет — для внятного сообщения об ошибке
    }

    private static String relativeOrAbsolute(Path base, Path target) {
        try {
            return base.toAbsolutePath().relativize(target.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return target.toAbsolutePath().toString();
        }
    }
}
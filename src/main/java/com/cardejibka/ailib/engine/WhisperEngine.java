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

public class WhisperEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-STT");

    public static String transcribe(Path wavAudioPath) {
        Path nativesDir = NativeConfig.getNativesDir();

        // Рабочей директорией будет папка Release, так как там лежат все .dll от Whisper
        Path workingDir = nativesDir.resolve("Release");
        Path whisperCli = workingDir.resolve("whisper-cli.exe");

        if (!Files.exists(whisperCli)) {
            whisperCli = workingDir.resolve("main.exe");
        }

        Path modelPath = NativeConfig.getModelsDir().resolve(NativeConfig.ModelFile.STT_MODEL.getFileName());

        if (!Files.exists(whisperCli) || !Files.exists(wavAudioPath)) {
            return "Ошибка: whisper-cli.exe или файл WAV не найден!";
        }

        List<String> command = new ArrayList<>();

        // Сам бинарник передаем абсолютным путем
        command.add(whisperCli.toAbsolutePath().toString());

        // Относительный путь до модели (защита от кириллицы)
        command.add("-m");
        command.add(workingDir.toAbsolutePath().relativize(modelPath.toAbsolutePath()).toString());

        // Относительный путь до аудиофайла (защита от кириллицы)
        command.add("-f");
        command.add(workingDir.toAbsolutePath().relativize(wavAudioPath.toAbsolutePath()).toString());

        command.add("-l");
        command.add("auto");
        command.add("-t");
        command.add("2");
        command.add("-nt");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());

        try {
            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append(" ");
                }
            }

            process.waitFor();
            return stdout.toString().trim();
        } catch (Exception e) {
            LOGGER.error("[STT Error]", e);
            return "Ошибка STT: " + e.getMessage();
        }
    }
}
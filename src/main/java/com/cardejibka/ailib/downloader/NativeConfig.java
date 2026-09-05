package com.cardejibka.ailib.downloader;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;

public class NativeConfig {

    public enum AiModule {
        LLM(
                "llama",
                "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-win-cpu-x64.zip",
                "llama-cli.exe"
        ),
        STT(
                "whisper",
                "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-x64.zip",
                "Release/whisper-cli.exe"
        ),
        TTS(
                "piper",
                "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip",
                "piper.exe"
        );

        private final String id;
        private final String downloadUrl;
        private final String expectedFile;

        AiModule(String id, String downloadUrl, String expectedFile) {
            this.id = id;
            this.downloadUrl = downloadUrl;
            this.expectedFile = expectedFile;
        }

        public String getId() { return id; }
        public String getDownloadUrl() { return downloadUrl; }
        public String getExpectedFile() { return expectedFile; }
        public String getArchiveName() { return id + "-native.zip"; }

        public Path getDir() {
            return getNativesDir();
        }
    }

    public enum ModelFile {
        LLM_MODEL("Llama-3.2-1B-Instruct-Q4_K_M.gguf", "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
        STT_MODEL("ggml-tiny.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"),
        TTS_MODEL("ru_RU-dmitri-medium.onnx", "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx"),
        TTS_CONFIG("ru_RU-dmitri-medium.onnx.json", "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx.json");

        private final String fileName;
        private final String downloadUrl;

        ModelFile(String fileName, String downloadUrl) {
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
        }

        public String getFileName() { return fileName; }
        public String getDownloadUrl() { return downloadUrl; }
    }

    public static Path getNativesDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ai_natives");
    }

    public static Path getModelsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ai_models");
    }
}
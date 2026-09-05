package com.cardejibka.ailib.downloader;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;


public class NativeConfig {

    public record PlatformBinary(String downloadUrl, String expectedFile) {}

    public enum AiModule {
        LLM("llama", true, buildLlamaBinaries()),
        STT("whisper", false, buildWhisperBinaries()),
        TTS("piper", true, buildPiperBinaries());

        private final String id;

        private final boolean stripRootFolder;
        private final Map<OperatingSystem, PlatformBinary> binaries;

        AiModule(String id, boolean stripRootFolder, Map<OperatingSystem, PlatformBinary> binaries) {
            this.id = id;
            this.stripRootFolder = stripRootFolder;
            this.binaries = binaries;
        }

        public String getId() {
            return id;
        }

        public boolean isStripRootFolder() {
            return stripRootFolder;
        }

        public PlatformBinary getBinary() {
            OperatingSystem os = OperatingSystem.detect();
            PlatformBinary binary = binaries.get(os);
            if (binary == null) {
                throw new UnsupportedOperationException(
                        "Модуль '" + id + "' пока не поддерживает ОС " + os.getId()
                                + ". Поддерживаются: " + binaries.keySet());
            }
            return binary;
        }

        public String getDownloadUrl() {
            return getBinary().downloadUrl();
        }

        public String getExpectedFile() {
            return getBinary().expectedFile();
        }

        public String getArchiveName() {
            return id + "-native.zip";
        }

        public Path getDir() {
            return getNativesDir().resolve(id);
        }
    }

    private static Map<OperatingSystem, PlatformBinary> buildLlamaBinaries() {
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        map.put(OperatingSystem.WINDOWS, new PlatformBinary(
                "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-win-cpu-x64.zip",
                "llama-cli.exe"));
        map.put(OperatingSystem.LINUX, new PlatformBinary(
                "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-ubuntu-x64.zip",
                "build/bin/llama-cli"));
        // На macOS доступны отдельные сборки под Apple Silicon и Intel.
        map.put(OperatingSystem.MACOS, Architecture.detect() == Architecture.ARM64
                ? new PlatformBinary(
                "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-macos-arm64.zip",
                "build/bin/llama-cli")
                : new PlatformBinary(
                "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-macos-x64.zip",
                "build/bin/llama-cli"));
        return map;
    }

    private static Map<OperatingSystem, PlatformBinary> buildWhisperBinaries() {
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        map.put(OperatingSystem.WINDOWS, new PlatformBinary(
                "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-x64.zip",
                "Release/whisper-cli.exe"));
        map.put(OperatingSystem.LINUX, new PlatformBinary(
                "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-linux-x64.zip",
                "whisper-cli"));
        map.put(OperatingSystem.MACOS, new PlatformBinary(
                "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-macos-arm64.zip",
                "whisper-cli"));
        return map;
    }

    private static Map<OperatingSystem, PlatformBinary> buildPiperBinaries() {
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        map.put(OperatingSystem.WINDOWS, new PlatformBinary(
                "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip",
                "piper.exe"));
        map.put(OperatingSystem.LINUX, new PlatformBinary(
                "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz",
                "piper"));
        map.put(OperatingSystem.MACOS, Architecture.detect() == Architecture.ARM64
                ? new PlatformBinary(
                "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_macos_aarch64.tar.gz",
                "piper")
                : new PlatformBinary(
                "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_macos_x64.tar.gz",
                "piper"));
        return map;
    }

    public enum ModelFile {
        LLM_MODEL("Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
        STT_MODEL("ggml-tiny.bin",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"),
        TTS_MODEL("ru_RU-dmitri-medium.onnx",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx"),
        TTS_CONFIG("ru_RU-dmitri-medium.onnx.json",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx.json");

        private final String fileName;
        private final String downloadUrl;

        ModelFile(String fileName, String downloadUrl) {
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
        }

        public String getFileName() {
            return fileName;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }
    }

    public static Path getNativesDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ai_natives");
    }

    public static Path getModelsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ai_models");
    }
}
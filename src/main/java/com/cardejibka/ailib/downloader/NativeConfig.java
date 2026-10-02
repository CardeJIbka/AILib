package com.cardejibka.ailib.downloader;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class NativeConfig {

    /** sha256 необязателен, но для исполняемых файлов его стоит заполнить. */
    public record PlatformBinary(String downloadUrl, String expectedFile, String sha256) {
        public PlatformBinary(String downloadUrl, String expectedFile) {
            this(downloadUrl, expectedFile, null);
        }
    }

    public enum AiModule {
        LLM("llama", true, NativeConfig::buildLlamaBinaries),
        STT("whisper", false, NativeConfig::buildWhisperBinaries),
        TTS("piper", true, NativeConfig::buildPiperBinaries);

        private final String id;
        private final boolean stripRootFolder;
        // Лениво: Architecture.detect() может бросить на экзотической архитектуре, и это
        // не должно ронять загрузку класса (ExceptionInInitializerError убил бы всю библиотеку).
        private final Supplier<Map<OperatingSystem, PlatformBinary>> binarySupplier;
        private volatile Map<OperatingSystem, PlatformBinary> binaries;
        private volatile Path resolvedExecutable;

        AiModule(String id, boolean stripRootFolder, Supplier<Map<OperatingSystem, PlatformBinary>> binarySupplier) {
            this.id = id;
            this.stripRootFolder = stripRootFolder;
            this.binarySupplier = binarySupplier;
        }

        public String getId() {
            return id;
        }

        public boolean isStripRootFolder() {
            return stripRootFolder;
        }

        /** @throws UnsupportedOperationException если для этой ОС/архитектуры бинарника нет */
        public PlatformBinary getBinary() {
            Map<OperatingSystem, PlatformBinary> map = binaries;
            if (map == null) {
                synchronized (this) {
                    map = binaries;
                    if (map == null) {
                        map = binarySupplier.get();
                        binaries = map;
                    }
                }
            }
            OperatingSystem os = OperatingSystem.detect();
            PlatformBinary binary = map.get(os);
            if (binary == null) {
                throw new UnsupportedOperationException("Модуль '" + id + "' не поддерживает сочетание ОС "
                        + os.getId() + " / архитектура " + System.getProperty("os.arch"));
            }
            return binary;
        }

        public String getDownloadUrl() {
            return getBinary().downloadUrl();
        }

        public String getExpectedFile() {
            return getBinary().expectedFile();
        }

        public String getSha256() {
            return getBinary().sha256();
        }

        public String getArchiveName() {
            String url = getDownloadUrl();
            return id + "-native" + (url.endsWith(".tar.gz") ? ".tar.gz" : ".zip");
        }

        public Path getDir() {
            return getNativesDir().resolve(id);
        }

        public Path getInstalledMarker() {
            return getDir().resolve(".installed");
        }

        public void markInstalled() throws IOException {
            Files.writeString(getInstalledMarker(), getDownloadUrl());
            resolvedExecutable = null;
        }

        /**
         * Путь к исполняемому файлу или null, если натив не установлен полностью.
         * Требует маркер .installed (его пишут только после успешной распаковки) и ищет
         * бинарник по имени, если раскладка архива не совпала с ожидаемой.
         */
        public Path resolveExecutable() {
            try {
                if (!Files.exists(getInstalledMarker())) return null;
                Path cached = resolvedExecutable;
                if (cached != null && Files.exists(cached)) return cached;

                Path dir = getDir();
                Path expected = dir.resolve(getExpectedFile());
                if (Files.isRegularFile(expected)) {
                    resolvedExecutable = expected;
                    return expected;
                }

                String baseName = Path.of(getExpectedFile()).getFileName().toString();
                try (Stream<Path> walk = Files.walk(dir, 4)) {
                    Optional<Path> found = walk
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals(baseName))
                            .min(Comparator.comparingInt(Path::getNameCount));
                    if (found.isPresent()) {
                        resolvedExecutable = found.get();
                        return found.get();
                    }
                }
                return null;
            } catch (UnsupportedOperationException | IOException e) {
                return null;
            }
        }
    }

    private static Map<OperatingSystem, PlatformBinary> buildLlamaBinaries() {
        Architecture arch = Architecture.detect();
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        // Windows ARM запускает x64 через эмуляцию, поэтому x64-сборка подходит всем.
        map.put(OperatingSystem.WINDOWS, new PlatformBinary(
                "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-win-cpu-x64.zip",
                "llama-cli.exe"));
        if (arch == Architecture.X64) {
            map.put(OperatingSystem.LINUX, new PlatformBinary(
                    "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-ubuntu-x64.zip",
                    "build/bin/llama-cli"));
        }
        map.put(OperatingSystem.MACOS, arch == Architecture.ARM64
                ? new PlatformBinary(
                "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-macos-arm64.zip",
                "build/bin/llama-cli")
                : new PlatformBinary(
                "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-macos-x64.zip",
                "build/bin/llama-cli"));
        return map;
    }

    private static Map<OperatingSystem, PlatformBinary> buildWhisperBinaries() {
        Architecture arch = Architecture.detect();
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        map.put(OperatingSystem.WINDOWS, new PlatformBinary(
                "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-x64.zip",
                "Release/whisper-cli.exe"));
        if (arch == Architecture.X64) {
            map.put(OperatingSystem.LINUX, new PlatformBinary(
                    "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-linux-x64.zip",
                    "whisper-cli"));
        }
        if (arch == Architecture.ARM64) { // для Intel Mac бинарника в этом релизе нет
            map.put(OperatingSystem.MACOS, new PlatformBinary(
                    "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-macos-arm64.zip",
                    "whisper-cli"));
        }
        return map;
    }

    private static Map<OperatingSystem, PlatformBinary> buildPiperBinaries() {
        Architecture arch = Architecture.detect();
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        map.put(OperatingSystem.WINDOWS, new PlatformBinary(
                "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip",
                "piper.exe"));
        if (arch == Architecture.X64) {
            map.put(OperatingSystem.LINUX, new PlatformBinary(
                    "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz",
                    "piper"));
        }
        map.put(OperatingSystem.MACOS, arch == Architecture.ARM64
                ? new PlatformBinary(
                "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_macos_aarch64.tar.gz",
                "piper")
                : new PlatformBinary(
                "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_macos_x64.tar.gz",
                "piper"));
        return map;
    }

    public static Path getNativesDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ai_natives");
    }

    public static Path getModelsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ai_models");
    }
}

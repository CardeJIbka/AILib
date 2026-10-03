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

    /** The sha256 is optional in the record, but for executables it should always be filled in. */
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
        // Lazy on purpose: Architecture.detect() may throw on an exotic architecture, and that must not
        // break class loading (an ExceptionInInitializerError would take the whole library down).
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

        /** @throws UnsupportedOperationException if there is no binary for this OS/architecture */
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
                throw new UnsupportedOperationException("Module '" + id + "' has no prebuilt binary for OS "
                        + os.getId() + " / architecture " + System.getProperty("os.arch"));
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
         * Path of the executable, or null if the native is not completely installed. Requires the
         * .installed marker (written only after a successful extraction) and falls back to searching
         * for the binary by name if the archive layout differs from the expected one.
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

    private static final String LLAMA = "https://github.com/ggml-org/llama.cpp/releases/download/b10549/llama-b10549-bin-";
    private static final String PIPER = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/";

    // The sha256 values were computed from the actually downloaded release files. When bumping a version,
    // recompute them: sha256sum <file> / shasum -a 256 <file> / Get-FileHash <file> -Algorithm SHA256.
    private static Map<OperatingSystem, PlatformBinary> buildLlamaBinaries() {
        boolean arm = Architecture.detect() == Architecture.ARM64;
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        // llama.cpp ships Linux/macOS builds as .tar.gz; the root folder llama-bXXXX/ is stripped on extraction.
        map.put(OperatingSystem.WINDOWS, arm
                ? new PlatformBinary(LLAMA + "win-cpu-arm64.zip", "llama-cli.exe",
                "88453b6c9ca186885ac22b3505f5591381068d830ebc622a499af73a3607d8c2")
                : new PlatformBinary(LLAMA + "win-cpu-x64.zip", "llama-cli.exe",
                "11d38f2ed878489b2c3d02b3d1a67683c02fbfb3d265876b9ede749a8dff5f1c"));
        map.put(OperatingSystem.LINUX, arm
                ? new PlatformBinary(LLAMA + "ubuntu-arm64.tar.gz", "llama-cli",
                "461d4b8775807fe39a418ea82b69c477e0e861ab8c5141af20d9c2c4975a3f2a")
                : new PlatformBinary(LLAMA + "ubuntu-x64.tar.gz", "llama-cli",
                "66b26d8cb3ab8edaf5a12bfe642b8f00844925f614f196a96a222b7ed1582c1d"));
        map.put(OperatingSystem.MACOS, arm
                ? new PlatformBinary(LLAMA + "macos-arm64.tar.gz", "llama-cli",
                "71e4b31afb020d6b71894eb8d1f2c0693038aec3f41f672f9fafb5055c8f2226")
                : new PlatformBinary(LLAMA + "macos-x64.tar.gz", "llama-cli",
                "94177680843a187881ae54021bbad8211c40797cab0df923ef17ee735e3ade09"));
        return map;
    }

    private static Map<OperatingSystem, PlatformBinary> buildWhisperBinaries() {
        Architecture.detect(); // throws UnsupportedOperationException on an exotic architecture
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        // whisper.cpp v1.8.4 publishes prebuilt binaries ONLY for Windows (x64; runs under emulation on
        // Windows ARM). For Linux/macOS the release has just an xcframework and a jar, so STT is unavailable
        // there until a different engine is plugged in via AiLib.setSttEngine.
        map.put(OperatingSystem.WINDOWS, new PlatformBinary(
                "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-x64.zip",
                "Release/whisper-cli.exe",
                "74f973345cb52ef5ba3ec9e7e7af8e48cc8c71722d1528603b80588a11f82e3e"));
        return map;
    }

    private static Map<OperatingSystem, PlatformBinary> buildPiperBinaries() {
        Architecture arch = Architecture.detect();
        Map<OperatingSystem, PlatformBinary> map = new EnumMap<>(OperatingSystem.class);
        map.put(OperatingSystem.WINDOWS, new PlatformBinary(PIPER + "piper_windows_amd64.zip", "piper.exe",
                "f3c58906402b24f3a96d92145f58acba6d86c9b5db896d207f78dc80811efcea"));
        if (arch == Architecture.X64) {
            map.put(OperatingSystem.LINUX, new PlatformBinary(PIPER + "piper_linux_x86_64.tar.gz", "piper",
                    "a50cb45f355b7af1f6d758c1b360717877ba0a398cc8cbe6d2a7a3a26e225992"));
        }
        map.put(OperatingSystem.MACOS, arch == Architecture.ARM64
                ? new PlatformBinary(PIPER + "piper_macos_aarch64.tar.gz", "piper",
                "6b1eb03b3735946cb35216e063e7eebcc33a6bbf5dd96ec0217959bf1cdcb0cc")
                : new PlatformBinary(PIPER + "piper_macos_x64.tar.gz", "piper",
                "ced85c0a3df13945b1e623b878a48fdc2854d5c485b4b67f62857cf551deaf8b"));
        return map;
    }

    public static Path getNativesDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ai_natives");
    }

    public static Path getModelsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ai_models");
    }
}

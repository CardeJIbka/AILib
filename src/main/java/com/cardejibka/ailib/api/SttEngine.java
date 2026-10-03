package com.cardejibka.ailib.api;

import java.nio.file.Path;

public interface SttEngine {
    boolean isNativeReady();

    /** @param wavAudioPath 16 kHz mono WAV file */
    String transcribe(Path wavAudioPath, Path modelPath);
}

package com.cardejibka.ailib.api;

import java.nio.file.Path;

public interface SttEngine {
    boolean isNativeReady();

    String transcribe(Path wavAudioPath, Path modelPath);
}
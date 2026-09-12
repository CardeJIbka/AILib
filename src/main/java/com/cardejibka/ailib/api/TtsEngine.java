package com.cardejibka.ailib.api;

import java.nio.file.Path;

public interface TtsEngine {
    boolean isNativeReady();

    /** @param voiceModelPath путь к .onnx-модели голоса; конфиг (.onnx.json) ищется рядом с тем же именем + ".json" */
    byte[] synthesize(String text, Path voiceModelPath);
}
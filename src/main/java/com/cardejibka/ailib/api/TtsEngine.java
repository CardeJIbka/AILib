package com.cardejibka.ailib.api;

import java.nio.file.Path;

public interface TtsEngine {
    boolean isNativeReady();

    /** @param voiceModelPath path to the .onnx voice; its config (.onnx.json) is expected next to it */
    byte[] synthesize(String text, Path voiceModelPath);
}

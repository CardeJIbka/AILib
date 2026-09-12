package com.cardejibka.ailib.api;

import java.nio.file.Path;

/**
 * Реализуется движком (по умолчанию — LlamaEngine на llama.cpp). Другой мод может
 * подставить свою реализацию, если ему нужен другой рантайм (например, llama-server
 * вместо разового процесса llama-cli).
 */
public interface LlmEngine {
    boolean isNativeReady();

    /**
     * @param prompt    пользовательский промпт (обрамление системным промптом — забота реализации)
     * @param modelPath путь к файлу весов на диске, уже гарантированно скачанному вызывающей стороной
     */
    String generate(String prompt, Path modelPath);
}
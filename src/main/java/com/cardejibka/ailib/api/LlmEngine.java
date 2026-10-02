package com.cardejibka.ailib.api;

import java.nio.file.Path;

/**
 * Реализуется движком (по умолчанию — LlamaEngine на llama.cpp). Подменить можно
 * через {@link AiLib#setLlmEngine(LlmEngine)}.
 */
public interface LlmEngine {
    boolean isNativeReady();

    /**
     * @param request   промпт и параметры; request.format() уже заполнен вызывающей стороной
     * @param modelPath путь к файлу весов, уже гарантированно скачанному
     */
    String generate(LlmRequest request, Path modelPath);

    default String generate(String prompt, Path modelPath) {
        return generate(LlmRequest.of(prompt), modelPath);
    }
}

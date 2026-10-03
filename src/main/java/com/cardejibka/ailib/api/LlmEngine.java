package com.cardejibka.ailib.api;

import java.nio.file.Path;

/**
 * Implemented by an LLM backend (default: LlamaEngine on top of llama.cpp).
 * Replace it with {@link AiLib#setLlmEngine(LlmEngine)}.
 */
public interface LlmEngine {
    /** Whether the backend's own runtime is usable. For custom engines AiLib relies on this instead of its built-in natives. */
    boolean isNativeReady();

    /**
     * @param request   prompt and parameters; {@code request.format()} is already resolved by the caller
     * @param modelPath path to the weights file, guaranteed to be downloaded already
     */
    String generate(LlmRequest request, Path modelPath);

    default String generate(String prompt, Path modelPath) {
        return generate(LlmRequest.of(prompt), modelPath);
    }
}

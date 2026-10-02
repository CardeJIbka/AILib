package com.cardejibka.ailib.api;

import java.util.List;

/**
 * Шаблон чата, который ожидает конкретная семья моделей. Раньше в LlamaEngine
 * был зашит только Llama 3, из-за чего любая другая модель отвечала мусором.
 */
public enum PromptFormat {
    LLAMA3,
    CHATML,   // Qwen, многие Mistral-файнтюны, Phi-3 (совместим в большинстве случаев)
    GEMMA,
    RAW;      // без шаблона — промпт уходит как есть

    /** Маркеры конца ответа от всех поддерживаемых форматов + служебные строки llama-cli. */
    public static final List<String> STOP_MARKERS = List.of(
            "<|eot_id|>", "<|im_end|>", "<end_of_turn>", "<|end_of_text|>",
            "<|endoftext|>", "</s>", "[end of text]", "[ Prompt:");

    public String build(String system, String user) {
        boolean hasSystem = system != null && !system.isBlank();
        return switch (this) {
            case LLAMA3 -> (hasSystem ? "<|start_header_id|>system<|end_header_id|>\n\n" + system + "<|eot_id|>" : "")
                    + "<|start_header_id|>user<|end_header_id|>\n\n" + user + "<|eot_id|>"
                    + "<|start_header_id|>assistant<|end_header_id|>\n\n";
            case CHATML -> (hasSystem ? "<|im_start|>system\n" + system + "<|im_end|>\n" : "")
                    + "<|im_start|>user\n" + user + "<|im_end|>\n"
                    + "<|im_start|>assistant\n";
            case GEMMA -> "<start_of_turn>user\n" + (hasSystem ? system + "\n\n" : "") + user
                    + "<end_of_turn>\n<start_of_turn>model\n";
            case RAW -> user;
        };
    }
}

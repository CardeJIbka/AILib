package com.cardejibka.ailib.api;

import java.util.List;

/**
 * Chat template expected by a given model family. Using the wrong template makes
 * a model answer with garbage, so it is part of the {@link ModelSpec}.
 */
public enum PromptFormat {
    LLAMA3,
    CHATML,   // Qwen, many Mistral fine-tunes, Phi-3 (compatible in most cases)
    GEMMA,
    RAW;      // no template: the prompt is sent as is

    /** End-of-answer markers of all supported formats plus llama-cli's own status line. */
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

package com.cardejibka.ailib.api;

/**
 * Параметры одного LLM-вызова. null-поля означают «взять из config/ailib.json»
 * (формат — из ModelSpec модели).
 */
public record LlmRequest(String prompt, String systemPrompt, Integer maxTokens, Double temperature,
                         PromptFormat format) {

    public LlmRequest {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("LlmRequest.prompt не может быть пустым");
        if (maxTokens != null && maxTokens < 1) throw new IllegalArgumentException("maxTokens должен быть >= 1");
        if (temperature != null && (temperature.isNaN() || temperature < 0)) {
            throw new IllegalArgumentException("temperature должна быть >= 0");
        }
    }

    public static LlmRequest of(String prompt) {
        return new LlmRequest(prompt, null, null, null, null);
    }

    public static Builder builder(String prompt) {
        return new Builder(prompt);
    }

    public LlmRequest withFormatIfAbsent(PromptFormat fallback) {
        return format != null ? this : new LlmRequest(prompt, systemPrompt, maxTokens, temperature, fallback);
    }

    public static final class Builder {
        private final String prompt;
        private String systemPrompt;
        private Integer maxTokens;
        private Double temperature;
        private PromptFormat format;

        private Builder(String prompt) {
            this.prompt = prompt;
        }

        public Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Builder maxTokens(int v) { this.maxTokens = v; return this; }
        public Builder temperature(double v) { this.temperature = v; return this; }
        public Builder format(PromptFormat v) { this.format = v; return this; }

        public LlmRequest build() {
            return new LlmRequest(prompt, systemPrompt, maxTokens, temperature, format);
        }
    }
}

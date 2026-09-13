package com.cardejibka.ailib;

import com.cardejibka.ailib.api.EngineType;
import com.cardejibka.ailib.api.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelSpecTest {

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () ->
                new ModelSpec("", EngineType.LLM, "model.gguf", "https://huggingface.co/x"));
    }

    @Test
    void rejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                new ModelSpec("mymod:model", EngineType.LLM, "model.gguf", "  "));
    }

    @Test
    void rejectsBlankFileName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ModelSpec("mymod:model", EngineType.LLM, "", "https://huggingface.co/x"));
    }

    @Test
    void sha256DefaultsToNull() {
        ModelSpec spec = new ModelSpec("mymod:model", EngineType.TTS, "voice.onnx", "https://huggingface.co/x");
        assertEquals(null, spec.sha256());
    }

    @Test
    void acceptsValidSpec() {
        ModelSpec spec = new ModelSpec("mymod:model", EngineType.STT, "model.bin",
                "https://huggingface.co/x", "deadbeef");
        assertEquals("mymod:model", spec.id());
        assertEquals("deadbeef", spec.sha256());
    }
}
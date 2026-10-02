package com.cardejibka.ailib;

import com.cardejibka.ailib.api.EngineType;
import com.cardejibka.ailib.api.ModelFile;
import com.cardejibka.ailib.api.ModelSpec;
import com.cardejibka.ailib.api.PromptFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelSpecTest {

    private static ModelSpec withFile(String fileName) {
        return new ModelSpec("mymod:model", EngineType.LLM, fileName, "https://huggingface.co/x");
    }

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
        assertThrows(IllegalArgumentException.class, () -> withFile(""));
    }

    @Test
    void rejectsPathTraversalInFileName() {
        assertThrows(IllegalArgumentException.class, () -> withFile("../../mods/evil.jar"));
        assertThrows(IllegalArgumentException.class, () -> withFile("sub/model.gguf"));
        assertThrows(IllegalArgumentException.class, () -> withFile("sub\\model.gguf"));
        assertThrows(IllegalArgumentException.class, () -> withFile("C:model.gguf"));
        assertThrows(IllegalArgumentException.class, () -> withFile(".hidden"));
        assertThrows(IllegalArgumentException.class, () -> withFile("model.gguf.part"));
    }

    @Test
    void rejectsNonHttpsUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                new ModelSpec("mymod:model", EngineType.LLM, "model.gguf", "http://huggingface.co/x"));
        assertThrows(IllegalArgumentException.class, () ->
                new ModelSpec("mymod:model", EngineType.LLM, "model.gguf", "file:///etc/passwd"));
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
        assertEquals(PromptFormat.LLAMA3, spec.promptFormat());
    }

    @Test
    void companionsAreIncludedInAllFiles() {
        ModelSpec spec = new ModelSpec("mymod:voice", EngineType.TTS, "v.onnx", "https://huggingface.co/v.onnx")
                .withCompanions(new ModelFile("v.onnx.json", "https://huggingface.co/v.onnx.json"));
        assertEquals(2, spec.allFiles().size());
    }

    @Test
    void rejectsDuplicateFileNamesAmongCompanions() {
        ModelSpec base = new ModelSpec("mymod:voice", EngineType.TTS, "v.onnx", "https://huggingface.co/v.onnx");
        assertThrows(IllegalArgumentException.class, () ->
                base.withCompanions(new ModelFile("V.ONNX", "https://huggingface.co/other")));
    }

    @Test
    void companionFileNamesAreValidatedToo() {
        assertThrows(IllegalArgumentException.class, () -> new ModelFile("../x", "https://huggingface.co/x"));
    }
}

package com.cardejibka.ailib;

import com.cardejibka.ailib.api.LlmRequest;
import com.cardejibka.ailib.api.PromptFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptFormatTest {

    @Test
    void llama3EndsWithAssistantHeader() {
        String p = PromptFormat.LLAMA3.build("sys", "hi");
        assertTrue(p.contains("system<|end_header_id|>\n\nsys<|eot_id|>"));
        assertTrue(p.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"));
    }

    @Test
    void chatmlWithoutSystemOmitsSystemBlock() {
        String p = PromptFormat.CHATML.build("", "hi");
        assertEquals("<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n", p);
    }

    @Test
    void gemmaMergesSystemIntoUserTurn() {
        String p = PromptFormat.GEMMA.build("sys", "hi");
        assertTrue(p.startsWith("<start_of_turn>user\nsys\n\nhi"));
    }

    @Test
    void rawIsPassedThrough() {
        assertEquals("hi", PromptFormat.RAW.build("sys", "hi"));
    }

    @Test
    void requestValidation() {
        assertThrows(IllegalArgumentException.class, () -> LlmRequest.of(" "));
        assertThrows(IllegalArgumentException.class, () -> LlmRequest.builder("x").maxTokens(0).build());
        assertEquals(PromptFormat.CHATML, LlmRequest.of("x").withFormatIfAbsent(PromptFormat.CHATML).format());
    }
}

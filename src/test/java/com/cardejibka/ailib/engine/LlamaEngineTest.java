package com.cardejibka.ailib.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlamaEngineTest {

    @Test
    void cutsAtStopMarkerAfterResponseMarker() {
        assertEquals("Hello", LlamaEngine.extractAnswer("echoed prompt ###RESPONSE###Hello<|eot_id|> junk"));
    }

    @Test
    void usesLastMarkerSoUserTextCannotFakeTheBoundary() {
        assertEquals("real", LlamaEngine.extractAnswer("###RESPONSE###fake ###RESPONSE###real\n[ Prompt: 1.0 t/s ]"));
    }

    @Test
    void withoutMarkerStillCutsAtStopMarkers() {
        assertEquals("plain answer", LlamaEngine.extractAnswer("plain answer [end of text]"));
    }

    @Test
    void stripsAnsiEscapes() {
        assertEquals("hi", LlamaEngine.extractAnswer("\u001B[0mhi"));
    }
}

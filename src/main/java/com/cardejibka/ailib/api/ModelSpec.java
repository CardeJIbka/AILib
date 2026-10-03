package com.cardejibka.ailib.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Describes a model. Besides the main file it may carry companion files
 * (e.g. the .onnx.json of a Piper voice); the model is ready only when ALL files are on disk.
 *
 * @param promptFormat chat template for LLM models (ignored for TTS/STT)
 */
public record ModelSpec(String id, EngineType engine, String fileName, String url, String sha256,
                        List<ModelFile> companions, PromptFormat promptFormat) {

    public ModelSpec {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("ModelSpec.id must not be blank");
        if (engine == null) throw new IllegalArgumentException("ModelSpec.engine must not be null");
        new ModelFile(fileName, url, sha256); // validates file name and url
        companions = companions == null ? List.of() : List.copyOf(companions);
        promptFormat = promptFormat == null ? PromptFormat.LLAMA3 : promptFormat;

        Set<String> seen = new HashSet<>();
        seen.add(fileName.toLowerCase(Locale.ROOT));
        for (ModelFile c : companions) {
            if (!seen.add(c.fileName().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate file name in model: " + c.fileName());
            }
        }
    }

    public ModelSpec(String id, EngineType engine, String fileName, String url) {
        this(id, engine, fileName, url, null, List.of(), PromptFormat.LLAMA3);
    }

    public ModelSpec(String id, EngineType engine, String fileName, String url, String sha256) {
        this(id, engine, fileName, url, sha256, List.of(), PromptFormat.LLAMA3);
    }

    public ModelSpec withCompanions(ModelFile... files) {
        List<ModelFile> all = new ArrayList<>(companions);
        all.addAll(List.of(files));
        return new ModelSpec(id, engine, fileName, url, sha256, all, promptFormat);
    }

    public ModelSpec withPromptFormat(PromptFormat format) {
        return new ModelSpec(id, engine, fileName, url, sha256, companions, format);
    }

    public ModelFile mainFile() {
        return new ModelFile(fileName, url, sha256);
    }

    /** Main file followed by the companions. */
    public List<ModelFile> allFiles() {
        List<ModelFile> all = new ArrayList<>(companions.size() + 1);
        all.add(mainFile());
        all.addAll(companions);
        return all;
    }
}

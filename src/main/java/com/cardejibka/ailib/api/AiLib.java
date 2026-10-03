package com.cardejibka.ailib.api;

import com.cardejibka.ailib.AiLibExecutors;
import com.cardejibka.ailib.config.AiLibConfig;
import com.cardejibka.ailib.downloader.AiLibBootstrap;
import com.cardejibka.ailib.downloader.NativeConfig;
import com.cardejibka.ailib.downloader.ProgressBus;
import com.cardejibka.ailib.downloader.ProgressSink;
import com.cardejibka.ailib.engine.LlamaEngine;
import com.cardejibka.ailib.engine.PiperEngine;
import com.cardejibka.ailib.engine.WhisperEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Public entry point of the library (early alpha: the API may still change before 1.0).
 * <pre>{@code
 * // Default model: nothing to register, it is downloaded on first use.
 * String answer = AiLib.generate("Hello!");
 *
 * // Your own model with a different chat template:
 * ModelSpec qwen = new ModelSpec("mymod:qwen", EngineType.LLM, "qwen2.5-1.5b-q4.gguf",
 *         "https://huggingface.co/.../qwen2.5-1.5b-q4.gguf").withPromptFormat(PromptFormat.CHATML);
 * ModelHandle handle = AiLib.register(qwen);   // never throws; inspect handle.state()/failureReason()
 * String reply = AiLib.generate(LlmRequest.builder("Hello!").systemPrompt("You are a guard").build(), qwen);
 * }</pre>
 * The synchronous methods block the calling thread while the native process runs: call them from
 * a background thread or use the {@code *Async} variants.
 */
public final class AiLib {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib");

    public static final ModelSpec DEFAULT_LLM_MODEL = new ModelSpec(
            "ailib:llama-3.2-1b-instruct-q4", EngineType.LLM,
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf");

    /** Piper voice: the .onnx.json is a companion file, so the voice is ready only when both files exist. */
    public static final ModelSpec DEFAULT_TTS_VOICE = new ModelSpec(
            "ailib:piper-ru-dmitri-medium", EngineType.TTS,
            "ru_RU-dmitri-medium.onnx",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx")
            .withCompanions(new ModelFile("ru_RU-dmitri-medium.onnx.json",
                    "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx.json"));

    public static final ModelSpec DEFAULT_STT_MODEL = new ModelSpec(
            "ailib:whisper-tiny", EngineType.STT,
            "ggml-tiny.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin");

    private static volatile LlmEngine llmEngine = new LlamaEngine();
    private static volatile TtsEngine ttsEngine = new PiperEngine();
    private static volatile SttEngine sttEngine = new WhisperEngine();

    private AiLib() {
    }

    // ---------- Engine replacement ----------

    /**
     * Replaces the LLM backend. A custom engine manages its own runtime: AiLib then relies on
     * {@link LlmEngine#isNativeReady()} and does not download llama.cpp for it.
     */
    public static void setLlmEngine(LlmEngine engine) { llmEngine = Objects.requireNonNull(engine); }

    public static void setTtsEngine(TtsEngine engine) { ttsEngine = Objects.requireNonNull(engine); }

    public static void setSttEngine(SttEngine engine) { sttEngine = Objects.requireNonNull(engine); }

    // ---------- Downloads and model management ----------

    /**
     * Called from AiLibMain.onInitialize(). With bootstrapDefaults=false in the config nothing is
     * downloaded here: everything is fetched lazily on the first API call. Modules whose engine was
     * replaced, or that have no prebuilt binary for this platform, are skipped.
     */
    public static void bootstrapDefaults() {
        if (!AiLibConfig.get().bootstrapDefaults) return;
        bootstrapModule(NativeConfig.AiModule.LLM, llmEngine instanceof LlamaEngine, DEFAULT_LLM_MODEL);
        bootstrapModule(NativeConfig.AiModule.TTS, ttsEngine instanceof PiperEngine, DEFAULT_TTS_VOICE);
        bootstrapModule(NativeConfig.AiModule.STT, sttEngine instanceof WhisperEngine, DEFAULT_STT_MODEL);
    }

    private static void bootstrapModule(NativeConfig.AiModule module, boolean builtIn, ModelSpec defaultModel) {
        if (!builtIn) return;
        try {
            module.getBinary();
        } catch (UnsupportedOperationException e) {
            LOGGER.info("{} is not available on this platform ({}); skipping its defaults", module.getId(), e.getMessage());
            return;
        }
        AiLibBootstrap.ensureNativeReady(module);
        register(defaultModel);
    }

    /**
     * Registers a model and starts downloading it in the background. Idempotent by {@code spec.id()};
     * registering a FAILED model again starts a new attempt. Never throws: problems are reported
     * as {@link ModelHandle.State#FAILED} with a reason.
     * <p>
     * Several mods may share the same file: models with identical file name, url and sha256 reuse it.
     */
    public static ModelHandle register(ModelSpec spec) {
        return AiLibBootstrap.register(spec);
    }

    /** Compatibility shortcut for {@code register(spec).future()}. Does not throw. */
    public static CompletableFuture<Boolean> registerModel(ModelSpec spec) {
        return register(spec).future();
    }

    /** Handle of a registered model, or null. */
    public static ModelHandle getModel(String id) {
        return AiLibBootstrap.getModel(id);
    }

    /** All registered models. */
    public static List<ModelHandle> models() {
        return AiLibBootstrap.getModels();
    }

    public static boolean isModelReady(ModelSpec spec) {
        return AiLibBootstrap.isModelReady(spec);
    }

    public static boolean isNativeReady(EngineType engine) {
        return switch (engine) {
            case LLM -> llmEngine.isNativeReady();
            case TTS -> ttsEngine.isNativeReady();
            case STT -> sttEngine.isNativeReady();
        };
    }

    /** Subscribes to the progress of every download (natives and models), e.g. for a custom HUD. */
    public static void subscribeProgress(ProgressSink sink) {
        ProgressBus.subscribe(sink);
    }

    public static void unsubscribeProgress(ProgressSink sink) {
        ProgressBus.unsubscribe(sink);
    }

    // ---------- LLM ----------

    public static String generate(String prompt) {
        return generate(LlmRequest.of(prompt), DEFAULT_LLM_MODEL);
    }

    public static String generate(String prompt, ModelSpec model) {
        return generate(LlmRequest.of(prompt), model);
    }

    public static String generate(LlmRequest request) {
        return generate(request, DEFAULT_LLM_MODEL);
    }

    public static String generate(LlmRequest request, ModelSpec model) {
        LlmEngine engine = llmEngine;
        requireReady(model, EngineType.LLM, NativeConfig.AiModule.LLM, engine instanceof LlamaEngine, engine.isNativeReady());
        Path modelPath = NativeConfig.getModelsDir().resolve(model.fileName());
        return engine.generate(request.withFormatIfAbsent(model.promptFormat()), modelPath);
    }

    public static CompletableFuture<String> generateAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> generate(prompt), AiLibExecutors.CALL_EXECUTOR);
    }

    public static CompletableFuture<String> generateAsync(LlmRequest request, ModelSpec model) {
        return CompletableFuture.supplyAsync(() -> generate(request, model), AiLibExecutors.CALL_EXECUTOR);
    }

    // ---------- TTS ----------

    public static byte[] synthesize(String text) {
        return synthesize(text, DEFAULT_TTS_VOICE);
    }

    public static byte[] synthesize(String text, ModelSpec voice) {
        TtsEngine engine = ttsEngine;
        requireReady(voice, EngineType.TTS, NativeConfig.AiModule.TTS, engine instanceof PiperEngine, engine.isNativeReady());
        Path voicePath = NativeConfig.getModelsDir().resolve(voice.fileName());
        return engine.synthesize(text, voicePath);
    }

    public static CompletableFuture<byte[]> synthesizeAsync(String text) {
        return CompletableFuture.supplyAsync(() -> synthesize(text), AiLibExecutors.CALL_EXECUTOR);
    }

    public static CompletableFuture<byte[]> synthesizeAsync(String text, ModelSpec voice) {
        return CompletableFuture.supplyAsync(() -> synthesize(text, voice), AiLibExecutors.CALL_EXECUTOR);
    }

    // ---------- STT ----------

    public static String transcribe(Path wavAudioPath) {
        return transcribe(wavAudioPath, DEFAULT_STT_MODEL);
    }

    public static String transcribe(Path wavAudioPath, ModelSpec model) {
        SttEngine engine = sttEngine;
        requireReady(model, EngineType.STT, NativeConfig.AiModule.STT, engine instanceof WhisperEngine, engine.isNativeReady());
        Path modelPath = NativeConfig.getModelsDir().resolve(model.fileName());
        return engine.transcribe(wavAudioPath, modelPath);
    }

    public static CompletableFuture<String> transcribeAsync(Path wavAudioPath) {
        return CompletableFuture.supplyAsync(() -> transcribe(wavAudioPath), AiLibExecutors.CALL_EXECUTOR);
    }

    public static CompletableFuture<String> transcribeAsync(Path wavAudioPath, ModelSpec model) {
        return CompletableFuture.supplyAsync(() -> transcribe(wavAudioPath, model), AiLibExecutors.CALL_EXECUTOR);
    }

    // ---------- Internals ----------

    /**
     * @param builtInEngine     true if the engine is one of the bundled ones (their natives are downloaded by AiLib)
     * @param engineNativeReady the engine's own readiness; the only native check for custom engines
     */
    private static void requireReady(ModelSpec spec, EngineType expected, NativeConfig.AiModule module,
                                     boolean builtInEngine, boolean engineNativeReady) {
        if (spec.engine() != expected) {
            throw new IllegalArgumentException("Model '" + spec.id() + "' is of type " + spec.engine()
                    + " but this call needs " + expected);
        }

        boolean nativeReady;
        if (builtInEngine) {
            try {
                module.getBinary();
            } catch (UnsupportedOperationException e) {
                throw new AiLibException(AiLibException.Reason.UNSUPPORTED_PLATFORM, e.getMessage());
            }
            nativeReady = AiLibBootstrap.isNativeReady(module);
            if (!nativeReady) {
                AiLibBootstrap.ensureNativeReady(module); // idempotent, with a cooldown after a failure
                if (AiLibBootstrap.hasNativeFailed(module)) {
                    throw new AiLibException(AiLibException.Reason.DOWNLOAD_FAILED,
                            "Failed to download the '" + module.getId() + "' native (will retry in a minute, see the log)");
                }
            }
        } else {
            nativeReady = engineNativeReady; // a custom engine manages its own runtime
        }

        // A model we have not seen yet is registered automatically.
        ModelHandle handle = AiLibBootstrap.getModel(spec.id());
        if (handle == null) handle = AiLibBootstrap.register(spec);

        if (handle.state() == ModelHandle.State.FAILED) {
            throw new AiLibException(AiLibException.Reason.DOWNLOAD_FAILED,
                    "Model '" + spec.id() + "' is not available (" + handle.failureReason() + "): " + handle.failureMessage());
        }
        if (nativeReady && handle.isReady()) return;

        boolean waitingForModel = !handle.isReady();
        String taskId = waitingForModel ? "model:" + spec.id() : "native:" + module.getId();
        int percent = ProgressBus.getLastPercent(taskId);
        throw new AiLibException(AiLibException.Reason.NOT_READY,
                "Component is not ready yet: " + (waitingForModel ? spec.fileName() : builtInEngine ? module.getId() : "engine runtime"),
                percent);
    }
}

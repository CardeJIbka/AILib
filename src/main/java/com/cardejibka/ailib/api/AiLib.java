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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Публичный вход в библиотеку для сторонних модов.
 * <pre>{@code
 * // Модель по умолчанию — ничего регистрировать не нужно (скачается при первом вызове):
 * String answer = AiLib.generate("Привет!");
 *
 * // Своя модель с другим шаблоном чата:
 * ModelSpec qwen = new ModelSpec("mymod:qwen", EngineType.LLM, "qwen2.5-1.5b-q4.gguf",
 *         "https://huggingface.co/.../qwen2.5-1.5b-q4.gguf").withPromptFormat(PromptFormat.CHATML);
 * ModelHandle handle = AiLib.register(qwen);   // не бросает; смотри handle.state()/failureReason()
 * String answer = AiLib.generate(LlmRequest.builder("Привет!").systemPrompt("Ты стражник").build(), qwen);
 * }</pre>
 * Синхронные методы блокируют поток на время работы нативного процесса — вызывай их из
 * фонового потока или используй {@code *Async}-варианты.
 */
public final class AiLib {

    public static final ModelSpec DEFAULT_LLM_MODEL = new ModelSpec(
            "ailib:llama-3.2-1b-instruct-q4", EngineType.LLM,
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf");

    /** Голос Piper: .onnx + .onnx.json как companion — модель готова, только когда есть оба файла. */
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

    // ---------- Подмена движков ----------

    public static void setLlmEngine(LlmEngine engine) { llmEngine = java.util.Objects.requireNonNull(engine); }
    public static void setTtsEngine(TtsEngine engine) { ttsEngine = java.util.Objects.requireNonNull(engine); }
    public static void setSttEngine(SttEngine engine) { sttEngine = java.util.Objects.requireNonNull(engine); }

    // ---------- Загрузки и управление моделями ----------

    /**
     * Вызывается из AiLibMain.onInitialize(). Если в конфиге bootstrapDefaults=false —
     * ничего не качает: всё подтянется лениво при первом вызове generate/synthesize/transcribe.
     */
    public static void bootstrapDefaults() {
        if (!AiLibConfig.get().bootstrapDefaults) return;
        AiLibBootstrap.ensureNativeReady(NativeConfig.AiModule.LLM);
        AiLibBootstrap.ensureNativeReady(NativeConfig.AiModule.TTS);
        AiLibBootstrap.ensureNativeReady(NativeConfig.AiModule.STT);
        register(DEFAULT_LLM_MODEL);
        register(DEFAULT_TTS_VOICE);
        register(DEFAULT_STT_MODEL);
    }

    /**
     * Регистрирует модель и запускает фоновую загрузку. Идемпотентно по {@code spec.id()};
     * повторный вызов для упавшей модели запускает новую попытку. Не бросает исключений —
     * проблемы приходят как {@link ModelHandle.State#FAILED} с причиной.
     */
    public static ModelHandle register(ModelSpec spec) {
        return AiLibBootstrap.register(spec);
    }

    /** Совместимость: то же, что register(spec).future(). Больше не бросает исключений. */
    public static CompletableFuture<Boolean> registerModel(ModelSpec spec) {
        return register(spec).future();
    }

    /** Хэндл зарегистрированной модели или null. */
    public static ModelHandle getModel(String id) {
        return AiLibBootstrap.getModel(id);
    }

    /** Все зарегистрированные модели. */
    public static List<ModelHandle> models() {
        return AiLibBootstrap.getModels();
    }

    public static boolean isModelReady(ModelSpec spec) {
        return AiLibBootstrap.isModelReady(spec);
    }

    public static boolean isNativeReady(EngineType engine) {
        return AiLibBootstrap.isNativeReady(toModule(engine));
    }

    /** Подписка на прогресс всех загрузок (нативы и модели) — для собственного HUD/логов. */
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
        requireReady(NativeConfig.AiModule.LLM, model, EngineType.LLM);
        Path modelPath = NativeConfig.getModelsDir().resolve(model.fileName());
        return llmEngine.generate(request.withFormatIfAbsent(model.promptFormat()), modelPath);
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
        requireReady(NativeConfig.AiModule.TTS, voice, EngineType.TTS);
        Path voicePath = NativeConfig.getModelsDir().resolve(voice.fileName());
        return ttsEngine.synthesize(text, voicePath);
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
        requireReady(NativeConfig.AiModule.STT, model, EngineType.STT);
        Path modelPath = NativeConfig.getModelsDir().resolve(model.fileName());
        return sttEngine.transcribe(wavAudioPath, modelPath);
    }

    public static CompletableFuture<String> transcribeAsync(Path wavAudioPath) {
        return CompletableFuture.supplyAsync(() -> transcribe(wavAudioPath), AiLibExecutors.CALL_EXECUTOR);
    }

    public static CompletableFuture<String> transcribeAsync(Path wavAudioPath, ModelSpec model) {
        return CompletableFuture.supplyAsync(() -> transcribe(wavAudioPath, model), AiLibExecutors.CALL_EXECUTOR);
    }

    // ---------- служебное ----------

    private static void requireReady(NativeConfig.AiModule module, ModelSpec spec, EngineType expected) {
        if (spec.engine() != expected) {
            throw new IllegalArgumentException("Модель '" + spec.id() + "' имеет тип " + spec.engine()
                    + ", а для этого вызова нужен " + expected);
        }
        try {
            module.getBinary();
        } catch (UnsupportedOperationException e) {
            throw new AiLibException(AiLibException.Reason.UNSUPPORTED_PLATFORM, e.getMessage());
        }

        boolean nativeReady = AiLibBootstrap.isNativeReady(module);
        if (!nativeReady) {
            AiLibBootstrap.ensureNativeReady(module); // идемпотентно, с кулдауном после сбоя
            if (AiLibBootstrap.hasNativeFailed(module)) {
                throw new AiLibException(AiLibException.Reason.DOWNLOAD_FAILED,
                        "Не удалось скачать натив '" + module.getId() + "' (повтор через минуту, подробности в логе)");
            }
        }

        // Модель, про которую ещё не знали, регистрируется автоматически.
        ModelHandle handle = AiLibBootstrap.getModel(spec.id());
        if (handle == null) handle = AiLibBootstrap.register(spec);

        if (handle.state() == ModelHandle.State.FAILED) {
            throw new AiLibException(AiLibException.Reason.DOWNLOAD_FAILED,
                    "Модель '" + spec.id() + "' не загружена (" + handle.failureReason() + "): " + handle.failureMessage());
        }
        if (nativeReady && handle.isReady()) return;

        String taskId = !handle.isReady() ? "model:" + spec.id() : "native:" + module.getId();
        int percent = ProgressBus.getLastPercent(taskId);
        throw new AiLibException(AiLibException.Reason.NOT_READY,
                "Компонент ещё не готов: " + (!handle.isReady() ? spec.fileName() : module.getId()), percent);
    }

    private static NativeConfig.AiModule toModule(EngineType engine) {
        return switch (engine) {
            case LLM -> NativeConfig.AiModule.LLM;
            case TTS -> NativeConfig.AiModule.TTS;
            case STT -> NativeConfig.AiModule.STT;
        };
    }
}

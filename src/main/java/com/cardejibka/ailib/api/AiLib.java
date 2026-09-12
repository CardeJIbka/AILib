package com.cardejibka.ailib.api;

import com.cardejibka.ailib.downloader.AiLibBootstrap;
import com.cardejibka.ailib.downloader.NativeConfig;
import com.cardejibka.ailib.downloader.ProgressBus;
import com.cardejibka.ailib.engine.LlamaEngine;
import com.cardejibka.ailib.engine.PiperEngine;
import com.cardejibka.ailib.engine.WhisperEngine;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Публичный вход в библиотеку для сторонних модов. Пример использования из
 * другого мода:
 * <pre>{@code
 * // Вариант 1 — модель по умолчанию (Llama-3.2-1B), ничего регистрировать не нужно:
 * String answer = AiLib.generate("Привет!");
 *
 * // Вариант 2 — своя модель:
 * ModelSpec myModel = new ModelSpec("mymod:mistral-7b", EngineType.LLM,
 *         "mistral-7b-q4.gguf", "https://huggingface.co/.../mistral-7b-q4.gguf",
 *         "abcd1234...sha256...");
 * AiLib.registerModel(myModel);
 * String answer = AiLib.generate("Привет!", myModel);
 * }</pre>
 * Все методы генерации — синхронные (блокируют вызывающий поток на время работы
 * нативного процесса), поэтому вызывай их из фонового потока, а не из главного
 * потока клиента/сервера — так же, как это делает {@code AiLibMain} в своих командах.
 */
public final class AiLib {

    public static final ModelSpec DEFAULT_LLM_MODEL = new ModelSpec(
            "ailib:llama-3.2-1b-instruct-q4", EngineType.LLM,
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf");

    public static final ModelSpec DEFAULT_TTS_VOICE = new ModelSpec(
            "ailib:piper-ru-dmitri-medium", EngineType.TTS,
            "ru_RU-dmitri-medium.onnx",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx");
    // Конфиг голоса качается отдельным файлом рядом (см. registerModel ниже) —
    // Piper ожидает <fileName>.json рядом с .onnx.
    public static final ModelSpec DEFAULT_TTS_VOICE_CONFIG = new ModelSpec(
            "ailib:piper-ru-dmitri-medium-config", EngineType.TTS,
            "ru_RU-dmitri-medium.onnx.json",
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium.onnx.json");

    public static final ModelSpec DEFAULT_STT_MODEL = new ModelSpec(
            "ailib:whisper-tiny", EngineType.STT,
            "ggml-tiny.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin");

    private static final LlmEngine LLM_ENGINE = new LlamaEngine();
    private static final TtsEngine TTS_ENGINE = new PiperEngine();
    private static final SttEngine STT_ENGINE = new WhisperEngine();

    private AiLib() {
    }

    /** Вызывается один раз из AiLibMain.onInitialize() — качает натив + модели по умолчанию в фоне. */
    public static void bootstrapDefaults() {
        AiLibBootstrap.ensureNativeReady(NativeConfig.AiModule.LLM);
        AiLibBootstrap.ensureNativeReady(NativeConfig.AiModule.TTS);
        AiLibBootstrap.ensureNativeReady(NativeConfig.AiModule.STT);
        registerModel(DEFAULT_LLM_MODEL);
        registerModel(DEFAULT_TTS_VOICE);
        registerModel(DEFAULT_TTS_VOICE_CONFIG);
        registerModel(DEFAULT_STT_MODEL);
    }

    /**
     * Регистрирует модель и запускает её фоновую загрузку параллельно со всем
     * остальным. Можно вызывать из любого мода в любой момент — повторная
     * регистрация с тем же {@code spec.id()} безопасна (не плодит новых загрузок).
     */
    public static CompletableFuture<Boolean> registerModel(ModelSpec spec) {
        return AiLibBootstrap.ensureModelReady(spec);
    }

    public static boolean isModelReady(ModelSpec spec) {
        return AiLibBootstrap.isModelReady(spec);
    }

    public static boolean isNativeReady(EngineType engine) {
        return AiLibBootstrap.isNativeReady(toModule(engine));
    }

    // ---------- LLM ----------

    public static String generate(String prompt) {
        return generate(prompt, DEFAULT_LLM_MODEL);
    }

    public static String generate(String prompt, ModelSpec model) {
        requireReady(NativeConfig.AiModule.LLM, model);
        Path modelPath = NativeConfig.getModelsDir().resolve(model.fileName());
        return LLM_ENGINE.generate(prompt, modelPath);
    }

    // ---------- TTS ----------

    public static byte[] synthesize(String text) {
        return synthesize(text, DEFAULT_TTS_VOICE);
    }

    public static byte[] synthesize(String text, ModelSpec voice) {
        requireReady(NativeConfig.AiModule.TTS, voice);
        Path voicePath = NativeConfig.getModelsDir().resolve(voice.fileName());
        return TTS_ENGINE.synthesize(text, voicePath);
    }

    // ---------- STT ----------

    public static String transcribe(Path wavAudioPath) {
        return transcribe(wavAudioPath, DEFAULT_STT_MODEL);
    }

    public static String transcribe(Path wavAudioPath, ModelSpec model) {
        requireReady(NativeConfig.AiModule.STT, model);
        Path modelPath = NativeConfig.getModelsDir().resolve(model.fileName());
        return STT_ENGINE.transcribe(wavAudioPath, modelPath);
    }

    // ---------- служебное ----------

    private static void requireReady(NativeConfig.AiModule module, ModelSpec spec) {
        boolean nativeReady = AiLibBootstrap.isNativeReady(module);
        boolean modelReady = AiLibBootstrap.isModelReady(spec);
        if (nativeReady && modelReady) return;

        String taskId = !modelReady ? "model:" + spec.id() : "native:" + module.getId();
        int percent = ProgressBus.getLastPercent(taskId);
        throw new AiLibException(AiLibException.Reason.NOT_READY,
                "Компонент ещё не готов: " + (!modelReady ? spec.fileName() : module.getId()), percent);
    }

    private static NativeConfig.AiModule toModule(EngineType engine) {
        return switch (engine) {
            case LLM -> NativeConfig.AiModule.LLM;
            case TTS -> NativeConfig.AiModule.TTS;
            case STT -> NativeConfig.AiModule.STT;
        };
    }
}
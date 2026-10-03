# Changelog

## 0.1.0 (early alpha)

First public version. The API is **not stable** and may change before 1.0.

### Features
- Local LLM (llama.cpp), TTS (Piper) and STT (whisper.cpp) behind one small API: `AiLib.generate / synthesize / transcribe` plus `*Async` variants.
- Automatic background download of native binaries and models; natives are pinned by sha256.
- Register your own models: `AiLib.register(ModelSpec)` returns a `ModelHandle` (state, progress, failure reason, retry, delete).
- Multi-file models (`ModelSpec.withCompanions`), e.g. a Piper voice (`.onnx` + `.onnx.json`).
- Chat templates per model (`PromptFormat`: LLAMA3, CHATML, GEMMA, RAW) and per-call parameters (`LlmRequest`).
- Replaceable engines (`setLlmEngine / setTtsEngine / setSttEngine`); a custom engine manages its own runtime.
- Several mods can share one model file (identical file name + url + sha256).
- Progress subscription for custom HUDs (`AiLib.subscribeProgress`).
- `/ailib llm|tts|stt|models` (operators only by default), client-only `/ailib record|ask`.

### Safety and reliability
- Model file names are validated (no path traversal); URLs must be https; downloads are allow-listed by domain.
- Downloads go to `.part` files and are renamed atomically; sha256 and size are verified.
- Failed downloads are retryable instead of being cached forever.
- A broken `config/ailib.json` is never overwritten.
- Interrupted native installs are detected via an `.installed` marker.

### Known limitations
- STT works out of the box on Windows x64 only (whisper.cpp ships no prebuilt Linux/macOS binaries).
- Each request starts a fresh native process (the model is reloaded every time): expect seconds of latency.
- No streaming, no conversation history, no download cancellation.
- Not yet tested end to end on every platform.

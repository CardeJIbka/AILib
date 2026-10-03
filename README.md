# AI Lib

A **Fabric** library that gives mods a local, offline AI toolbox: an LLM ([llama.cpp](https://github.com/ggml-org/llama.cpp)),
text-to-speech ([Piper](https://github.com/rhasspy/piper)) and speech-to-text ([whisper.cpp](https://github.com/ggml-org/whisper.cpp)).
It downloads the native binaries and models by itself in the background and exposes a small, thread-safe Java API.

> **Status: early alpha (0.1.0).** The API may change before 1.0. Download, verification and extraction paths are
> checked; end-to-end behaviour still needs more testing on every platform.

## Supported platforms

| Module | Windows | Linux | macOS |
|--------|---------|-------|-------|
| LLM (llama.cpp) | x64, ARM64 | x64, ARM64 | x64, ARM64 |
| TTS (Piper) | x64 (ARM64 via emulation) | x64 | x64, ARM64 |
| STT (whisper.cpp) | x64 (ARM64 via emulation) | not built in* | not built in* |

\* whisper.cpp publishes no prebuilt Linux/macOS binaries. Plug in your own engine with `AiLib.setSttEngine(...)`.

Minecraft `1.21.2` – `1.21.5`, Java 21+, Fabric API.

## Quick start for mod authors

Declare the dependency in your `fabric.mod.json` (and depend on / jar-in-jar the library in Gradle):

```json
"depends": { "ailib": ">=0.1.0" }
```

> **Never shade or relocate AiLib.** Two relocated copies would download into the same folders and run
> duplicate native processes. Depend on it normally or use Loom's `include` (jar-in-jar) so Fabric loads exactly one copy.

```java
// Run these off the main thread, or use the *Async variants.
String reply = AiLib.generate("Greet the player in one sentence.");

AiLib.generateAsync(LlmRequest.builder("Describe this village")
        .systemPrompt("You are a medieval storyteller.")
        .maxTokens(200).temperature(0.8).build(), AiLib.DEFAULT_LLM_MODEL)
     .thenAccept(text -> { /* ... */ });

byte[] wav = AiLib.synthesize("Hello there!");
String heard = AiLib.transcribe(Path.of("recording.wav")); // 16 kHz mono WAV
```

### Your own model

```java
ModelSpec qwen = new ModelSpec("mymod:qwen2.5-1.5b", EngineType.LLM, "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "https://huggingface.co/<user>/<repo>/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "<sha256 of the file>")                       // sha256 is optional but recommended
        .withPromptFormat(PromptFormat.CHATML);

ModelHandle handle = AiLib.register(qwen);            // never throws
handle.state();          // DOWNLOADING | VERIFYING | READY | FAILED
handle.progress();       // 0..1, or -1 if unknown
handle.failureReason();  // DOMAIN_BLOCKED, FILENAME_COLLISION, NETWORK, HTTP_ERROR, INCOMPLETE, HASH_MISMATCH, UNKNOWN
handle.retry();          // after FAILED
handle.future();         // CompletableFuture<Boolean>
```

A voice with several files:

```java
ModelSpec voice = new ModelSpec("mymod:voice", EngineType.TTS, "en_US-amy-medium.onnx", "https://.../en_US-amy-medium.onnx")
        .withCompanions(new ModelFile("en_US-amy-medium.onnx.json", "https://.../en_US-amy-medium.onnx.json"));
```

Calls throw `AiLibException` with a `Reason` (`NOT_READY` carries `getProgressPercent()`, `BUSY`, `TIMEOUT`, `UNSUPPORTED_PLATFORM`, ...).
Handle them: a model may simply not be downloaded yet.

### Custom engines (e.g. Vosk, a llama-server backend)

Implement `LlmEngine` / `TtsEngine` / `SttEngine` and call `AiLib.setXxxEngine(...)` during your mod initialization.
A custom engine owns its runtime: AiLib asks `engine.isNativeReady()` instead of downloading its built-in natives.
Models are still described by `ModelSpec` (single files, optionally with companions). Archives/directories are not supported yet.
Set `bootstrapDefaults` to `false` in the config if your pack replaces engines, so the default downloads are skipped.

### Progress for your own HUD

```java
AiLib.subscribeProgress(new ProgressSink() {
    public void onProgress(String taskId, String label, long downloaded, long total) { /* ... */ }
    public void onFinished(String taskId, boolean success) { /* ... */ }
});
```

## Using it in a modpack

- One copy of AiLib is shared by all mods that depend on it; downloads are de-duplicated.
- Two models may share a file only if file name, url **and** sha256 are identical; otherwise the second registration fails with `FILENAME_COLLISION`.
- Each engine handles one request at a time. Other callers wait up to 30 s and then get `BUSY`.
- Model downloads are limited to hosts in `allowedModelDownloadDomains` (see below). Add a domain there for models hosted elsewhere.
- Defaults (about 1 GB: Llama 3.2 1B, a Piper voice, Whisper tiny) are downloaded at startup. Set `bootstrapDefaults: false` to download lazily instead.

## Configuration (`config/ailib.json`)

| Key | Default | Meaning |
|-----|---------|---------|
| `bootstrapDefaults` | `true` | Download default natives/models at startup (otherwise on first use). |
| `commandPermissionLevel` | `2` | Permission level for `/ailib` commands. |
| `llmSystemPrompt` | Russian assistant prompt | Default system prompt (overridable per call). |
| `llmMaxTokens`, `llmContextSize`, `llmTemperature`, `llmTimeoutSeconds` | `128`, `2048`, `0.6`, `35` | LLM defaults. |
| `llmExtraArgs` | `[]` | Extra `llama-cli` arguments, e.g. `["-no-cnv"]` if your llama.cpp build starts in conversation mode. |
| `ttsTimeoutSeconds` | `15` | TTS timeout. |
| `sttLanguage`, `sttThreads`, `sttTimeoutSeconds` | `auto`, `2`, `30` | STT settings. |
| `maxParallelDownloads` | `3` | Parallel download threads. |
| `allowedModelDownloadDomains` | huggingface.co, github.com, ... | Hosts models may be downloaded from (exact or subdomain). |

A syntactically broken file is never overwritten; defaults are used until you fix it.

## Commands

`/ailib llm <prompt>`, `/ailib tts <text>`, `/ailib stt <file inside the game folder>`, `/ailib models`,
and, on the client only, `/ailib record <seconds>` and `/ailib ask <seconds>` (microphone to LLM to speech).
Operators only by default.

## Security notes

- Natives are downloaded over https from fixed URLs and verified against pinned sha256 values.
- Model file names are validated (no paths, `..`, or drive prefixes); only https URLs are accepted.
- The allow-list is checked against the original host; redirects to CDNs are followed (https only, no downgrade).
- Player-supplied text sent to an LLM is not sanitized: treat model output as untrusted.
- Running local models uses real CPU and RAM. On a server, keep the commands restricted.

## Known limitations

- Each request starts a new native process, so the model is reloaded every time (seconds of latency).
- No token streaming, conversation history or download cancellation yet.
- STT input is a WAV file path only.
- The HUD only renders in-world, not in menus, and a failed download is not announced in the UI (check the log or `/ailib models`).

## License

MIT, see [LICENSE](LICENSE).

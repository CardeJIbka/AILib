# AI Lib

A Minecraft library that gives mods a local, offline AI toolbox: an LLM ([llama.cpp](https://github.com/ggml-org/llama.cpp)),
text-to-speech ([Piper](https://github.com/rhasspy/piper)) and speech-to-text ([whisper.cpp](https://github.com/ggml-org/whisper.cpp)).
It downloads the native binaries and models by itself in the background, **only when something actually needs them**,
and exposes a small, thread-safe Java API. After the one-time download everything runs on the player's machine: no cloud, no accounts.

> **Status: early alpha (0.1.x).** The API may change before 1.0. Download, verification and extraction paths are
> checked; end-to-end behaviour still needs more testing on every platform.

## Supported platforms

| Module | Windows | Linux | macOS |
|--------|---------|-------|-------|
| LLM (llama.cpp) | x64, ARM64 | x64, ARM64 | x64, ARM64 |
| TTS (Piper) | x64 (ARM64 via emulation) | x64 | x64, ARM64 |
| STT (whisper.cpp) | x64 (ARM64 via emulation) | not built in* | not built in* |

\* whisper.cpp publishes no prebuilt Linux/macOS binaries. Plug in your own engine with `AiLib.setSttEngine(...)`.

Minecraft `1.21` (the exact versions each release supports are listed on its Modrinth page), Java 21+, Fabric API.

## What gets downloaded, and when

**Nothing is downloaded at startup by default.** A component is fetched the first time a mod calls the API that needs it
(the call throws `AiLibException` with reason `NOT_READY` and a progress percentage until the download finishes).
Set `bootstrapDefaults` to `true` in the config to download all defaults at startup instead.

| Component | Source | Approx. size |
|-----------|--------|--------------|
| llama.cpp, Piper, whisper.cpp binaries | official GitHub releases, pinned by sha256 | tens of MB each |
| Default LLM: Llama 3.2 1B Instruct (Q4_K_M) | Hugging Face (bartowski GGUF) | ~0.8 GB |
| Default voice: Piper `en_US-ljspeech-medium` | Hugging Face (rhasspy/piper-voices) | ~64 MB |
| Default STT model: whisper `ggml-tiny` | Hugging Face (ggerganov/whisper.cpp) | ~75 MB |

Files go to `ai_natives/` and `ai_models/` inside the game directory. The only network connections AiLib makes are these
downloads (GitHub, Hugging Face and the CDNs they redirect to). There is no telemetry, and prompts, audio and replies never leave the machine.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the licenses of everything that is downloaded.

## Quick start for mod authors

Add the dependency in Gradle (Modrinth Maven) and declare it in your `fabric.mod.json`:

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { name = "Modrinth"; url = "https://api.modrinth.com/maven" } }
        filter { includeGroup "maven.modrinth" }
    }
}
dependencies {
    modImplementation "maven.modrinth:ailib:<version>"
    include "maven.modrinth:ailib:<version>"   // optional: jar-in-jar
}
```

```json
"depends": { "ailib": ">=0.1.0" }
```

> **Never shade or relocate AiLib.** Two relocated copies would download into the same folders and run
> duplicate native processes. Depend on it normally or use Loom's `include` (jar-in-jar) so Fabric loads exactly one copy.

```java
// Run these off the main thread, or use the *Async variants.
// The first call may throw NOT_READY while the model downloads: catch AiLibException and try again later.
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

A voice with several files, e.g. a voice for another language:

```java
ModelSpec voice = new ModelSpec("mymod:voice", EngineType.TTS, "ru_RU-dmitri-medium.onnx", "https://.../ru_RU-dmitri-medium.onnx")
        .withCompanions(new ModelFile("ru_RU-dmitri-medium.onnx.json", "https://.../ru_RU-dmitri-medium.onnx.json"));
AiLib.synthesize("Привет!", voice);
```

Check the license of every model you ship or register: it is your responsibility, not AiLib's.

Calls throw `AiLibException` with a `Reason` (`NOT_READY` carries `getProgressPercent()`, `BUSY`, `TIMEOUT`, `UNSUPPORTED_PLATFORM`, ...).
Handle them: a model may simply not be downloaded yet.

### Custom engines (e.g. Vosk, a llama-server backend)

Implement `LlmEngine` / `TtsEngine` / `SttEngine` and call `AiLib.setXxxEngine(...)` during your mod initialization.
A custom engine owns its runtime: AiLib asks `engine.isNativeReady()` instead of downloading its built-in natives.
Models are still described by `ModelSpec` (single files, optionally with companions). Archives/directories are not supported yet.

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
- To pre-download everything (for example in a modpack that ships with voice features), set `bootstrapDefaults: true`.

## Configuration (`config/ailib.json`)

| Key | Default | Meaning |
|-----|---------|---------|
| `bootstrapDefaults` | `false` | `true` = download the default natives/models at startup; `false` = on first use. |
| `commandPermissionLevel` | `2` | Permission level for `/ailib` commands. |
| `llmSystemPrompt` | `You are a helpful assistant. Answer briefly and clearly.` | Default system prompt (overridable per call). |
| `llmMaxTokens`, `llmContextSize`, `llmTemperature`, `llmTimeoutSeconds` | `128`, `2048`, `0.6`, `35` | LLM defaults. |
| `llmExtraArgs` | `["-no-cnv"]` | Extra `llama-cli` arguments. `-no-cnv` turns off llama-cli's own conversation mode (AiLib applies the chat template itself); set `[]` if your llama.cpp build does not know it. |
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

MIT, see [LICENSE](LICENSE). Downloaded third-party components have their own licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
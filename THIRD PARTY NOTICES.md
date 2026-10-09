# Notice of Third-Party Components

The license in the `LICENSE` file (MIT) applies only to the code of this library.
AiLib **downloads and runs** third-party binaries and models on the player's machine at runtime
(on first use, or at startup if `bootstrapDefaults` is enabled). They are not part of the AiLib jar and are distributed under their own terms.
Below is what exactly and under which terms.

## Native binaries

| Component                                              | License | Link                                                        |
| ------------------------------------------------------ | ------- | ----------------------------------------------------------- |
| [llama.cpp](https://github.com/ggml-org/llama.cpp)     | MIT     | https://github.com/ggml-org/llama.cpp/blob/master/LICENSE   |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT     | https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE |
| [piper](https://github.com/rhasspy/piper)              | MIT     | https://github.com/rhasspy/piper/blob/master/LICENSE.md     |

The binaries come from the projects' official GitHub releases and are verified against sha256 values pinned in AiLib.

## Default models

| Model | License | What this means in practice |
| ----- | ------- | --------------------------- |
| [Llama-3.2-1B-Instruct](https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct) (GGUF weights from bartowski) | **Llama 3.2 Community License** (not MIT/CC0) | Requires attribution ("Built with Llama") when distributing derivative products and imposes an [Acceptable Use Policy](https://www.llama.com/llama3_2/use-policy/): restrictions on a number of usage scenarios. Read the full license text on the model page before embedding it in a commercial or public project. |
| [ggml-tiny (Whisper)](https://huggingface.co/ggerganov/whisper.cpp) | MIT | No restrictions. |
| [Piper voice en_US-ljspeech-medium](https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/ljspeech/medium) | MIT (the piper-voices repository license); see the voice's `MODEL_CARD` for the training dataset | Check the `MODEL_CARD` of any voice you ship: voices differ in their dataset terms. |

## Models registered by third-party mods

If another mod registers its model via `AiLib.register(...)`,
**responsibility for that model's license lies with the developer of that mod**,
not with AiLib. The library only applies an allow-list of domains
(`config/ailib.json` -> `allowedModelDownloadDomains`) as a technical measure
against downloading from arbitrary sources. This is not a license check.

## What a mod author using AiLib should do

If you are building a mod based on AiLib and distributing it publicly (Modrinth,
CurseForge, etc.) while it uses the default Llama 3.2 model, mention in the mod description
a phrase like "Built with Llama" and provide a link to the Acceptable Use Policy, as required
by the model's license. This is a requirement of the model itself, not of AiLib.
If you register your own models, check their licenses the same way.
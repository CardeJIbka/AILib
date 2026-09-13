# Уведомление о сторонних компонентах

Лицензия в файле `LICENSE` (CC0-1.0) относится только к коду этой библиотеки.
AiLib во время работы **скачивает и запускает** сторонние бинарники и модели,
которые распространяются на собственных условиях. Ниже — что именно и на каких.

## Нативные бинарники

| Компонент | Лицензия | Ссылка |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | https://github.com/ggml-org/llama.cpp/blob/master/LICENSE |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT | https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE |
| [piper](https://github.com/rhasspy/piper) | MIT | https://github.com/rhasspy/piper/blob/master/LICENSE.md |

Обременений для конечного пользователя эти три компонента не накладывают —
свободное использование, изменение, распространение.

## Модели по умолчанию

| Модель | Лицензия | Что это значит на практике |
|---|---|---|
| [Llama-3.2-1B-Instruct](https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct) (веса в GGUF от bartowski) | **Llama 3.2 Community License** (не MIT/CC0) | Требует атрибуцию ("Built with Llama") при распространении производных продуктов и накладывает [Acceptable Use Policy](https://www.llama.com/llama3_2/use-policy/) — ограничения на ряд сценариев использования. Прочитай полный текст лицензии на странице модели, прежде чем встраивать её в коммерческий или публичный проект. |
| [ggml-tiny (Whisper)](https://huggingface.co/ggerganov/whisper.cpp) | MIT | Без ограничений. |
| [Piper voices (ru_RU-dmitri-medium)](https://huggingface.co/rhasspy/piper-voices) | MIT | Без ограничений. |

## Модели, зарегистрированные сторонними модами

Если другой мод регистрирует свою модель через `AiLib.registerModel(...)`,
**ответственность за лицензию этой модели лежит на разработчике того мода**,
а не на AiLib. Библиотека применяет только allow-list доменов
(`config/ailib.json` -> `allowedModelDownloadDomains`) как техническую меру
против скачивания с произвольных источников — это не проверка лицензии.

## Что стоит сделать автору мода, использующего AiLib

Если ты собираешь мод на основе AiLib и распространяешь его публично (Modrinth,
CurseForge и т.п.) с моделью Llama-3.2 по умолчанию — упомяни в описании мода
фразу вида "Built with Llama" и дай ссылку на Acceptable Use Policy, как того
требует лицензия модели. Это требование самой модели, не AiLib.
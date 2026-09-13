# AiLib

Fabric-библиотека для локального AI прямо в Minecraft: генерация текста (llama.cpp),
синтез речи (piper) и распознавание речи (whisper.cpp) — без облачных сервисов
и без API-ключей. Нужные бинарники и модели скачиваются автоматически в фоне,
параллельно, не блокируя запуск игры.

## Подключение

```gradle
repositories {
    maven { url "https://TODO-твой-maven-репозиторий" }
}

dependencies {
    modImplementation "com.cardejibka.ailib:ailib:${ailib_version}"
}
```

## Быстрый старт

Модель по умолчанию (Llama-3.2-1B) регистрируется и качается автоматически при
загрузке AiLib — ничего дополнительно делать не нужно:

```java
CompletableFuture.runAsync(() -> {
    try {
        String answer = AiLib.generate("Привет! Расскажи анекдот про крипер.");
        // ... что-то сделать с ответом
    } catch (AiLibException e) {
        // e.getReason() == NOT_READY / TIMEOUT / PROCESS_FAILED / BUSY / ...
        // e.getProgressPercent() — если NOT_READY, процент загрузки (или -1)
    }
});
```

**Важно:** методы `AiLib.generate/synthesize/transcribe` синхронные и блокируют
поток на время работы нативного процесса — всегда вызывай их из фонового потока,
никогда из главного потока клиента/сервера.

## Своя модель

Любой мод может зарегистрировать свою модель по прямой ссылке — она скачается
в фоне параллельно со всем остальным, независимо от того, кто и когда её
зарегистрировал:

```java
ModelSpec myModel = new ModelSpec(
        "mymod:mistral-7b",                     // уникальный id — используй свой modid как префикс
        EngineType.LLM,
        "mistral-7b-instruct-q4.gguf",          // имя файла в ai_models/
        "https://huggingface.co/.../mistral-7b-instruct-q4.gguf",
        "abcd1234...sha256...");                // необязательно, но настоятельно рекомендуется

AiLib.registerModel(myModel);

// ...позже, в фоновом потоке:
String answer = AiLib.generate("Привет!", myModel);
```

Домен ссылки должен входить в allow-list (`config/ailib.json` ->
`allowedModelDownloadDomains`, по умолчанию huggingface.co и github.com) — иначе
`registerModel` бросит `IllegalArgumentException`. Это защита от того, что чужой
мод незаметно для игрока тянет файлы с произвольных доменов через твою библиотеку.

## Своя реализация движка

Если тебе не подходит llama.cpp/piper/whisper.cpp (например, нужен llama-server
вместо разового процесса) — реализуй `LlmEngine`/`TtsEngine`/`SttEngine` из пакета
`com.cardejibka.ailib.api` самостоятельно. Пакеты `downloader` и `engine` — это
внутренняя реализация, не публичный контракт, полагаться на них напрямую не стоит.

## Конфиг

`config/ailib.json` создаётся автоматически при первом запуске:

| Поле | Что делает |
|---|---|
| `llmSystemPrompt` | системный промпт для LLM |
| `llmMaxTokens`, `llmContextSize`, `llmTemperature`, `llmTimeoutSeconds` | параметры генерации |
| `ttsTimeoutSeconds` | таймаут синтеза речи |
| `sttLanguage`, `sttThreads`, `sttTimeoutSeconds` | параметры распознавания |
| `maxParallelDownloads` | сколько артефактов может качаться одновременно |
| `allowedModelDownloadDomains` | allow-list доменов для моделей сторонних модов |

## Команды (для проверки/дебага)

- `/ailib llm <текст>` — генерация ответа
- `/ailib tts <текст>` — синтез речи, сохраняет и проигрывает (клиент)
- `/ailib stt <путь к wav>` — распознавание файла
- `/ailib record <секунды>` — запись с микрофона + распознавание (только клиент)
- `/ailib ask <секунды>` — голос → текст → LLM → голос (только клиент)

## Лицензии

Код самой библиотеки — CC0-1.0 (см. `LICENSE`). Скачиваемые в рантайме бинарники
и модели (llama.cpp, whisper.cpp, piper, веса моделей) распространяются на
собственных условиях — см. `THIRD_PARTY_NOTICES.md`. Ответственность за лицензию
модели, зарегистрированной через `AiLib.registerModel`, лежит на том, кто её
зарегистрировал.

## Версионирование

Пока `0.x.y` — публичный API (`com.cardejibka.ailib.api`) ещё может меняться
между минорными версиями. См. `CHANGELOG.md`.
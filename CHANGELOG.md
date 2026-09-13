# Changelog

Формат основан на [Keep a Changelog](https://keepachangelog.com/), версии — semver.
Пока `0.x.y` — любая минорная версия может содержать breaking changes в `api`-пакете.

## [Unreleased]

### Added
- Конфиг `config/ailib.json`: системный промпт, параметры генерации, таймауты,
  allow-list доменов для скачивания моделей сторонних модов.
- `package-info.java` для `downloader`/`engine`, явно помечающие их как внутреннюю
  реализацию (публичный контракт — только пакет `api`).
- Юнит-тесты на `ModelSpec` и allow-list доменов (`com.cardejibka.ailib.AiLibConfigTest`), не требуют
  Minecraft/Fabric окружения.

### Changed
- Размер пула параллельных загрузок (`AiLibExecutors.DOWNLOAD_EXECUTOR`) теперь
  берётся из конфига вместо хардкода.
- `LlamaEngine`/`PiperEngine`/`WhisperEngine` читают промпт/таймауты/потоки из
  `AiLibConfig` вместо магических чисел в коде.

## [0.1.0] — первая версия с публичным API

### Added
- Публичный фасад `AiLib` (`generate`/`synthesize`/`transcribe`, `registerModel`,
  `isModelReady`/`isNativeReady`).
- Реестр моделей `ModelSpec` — сторонние моды регистрируют модель по своей ссылке
  вместо жёстко зашитой в библиотеке.
- Параллельная фоновая загрузка нативов и моделей (`AiLibBootstrap`,
  `AiLibExecutors.DOWNLOAD_EXECUTOR`), не блокирующая запуск игры.
- HUD-оверлей прогресса загрузки на клиенте (`DownloadTracker` + `LoadingOverlay`),
  поддерживает несколько одновременных задач.
- Явное исключение `AiLibException` с кодом причины (`NOT_READY`, `TIMEOUT`,
  `PROCESS_FAILED`, `BUSY`, `CLIENT_ONLY`) вместо строк-ошибок в теле ответа.
- Кроссплатформенная поддержка натива (Windows/Linux/macOS, x64/arm64) для
  llama.cpp, whisper.cpp и piper.
- Fair-семафоры на движок (`LLM_SLOT`/`TTS_SLOT`/`STT_SLOT`), чтобы параллельные
  запросы от разных модов не грузили несколько тяжёлых моделей в память разом.

### Breaking (относительно первой версии, ещё не выпущенной публично)
- `LlamaEngine.generate(String)` / `PiperEngine.synthesize(String)` /
  `WhisperEngine.transcribe(Path)` статические методы заменены на инстанс-методы
  через интерфейсы `LlmEngine`/`TtsEngine`/`SttEngine`, принимающие явный путь к
  модели. Используй `AiLib.generate(...)` вместо прямого вызова классов движков.
- `NativeConfig.ModelFile` enum удалён — модели теперь регистрируются через
  `ModelSpec` + `AiLib.registerModel(...)`.
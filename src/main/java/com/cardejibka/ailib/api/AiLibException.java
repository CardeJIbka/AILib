package com.cardejibka.ailib.api;

public class AiLibException extends RuntimeException {

    public enum Reason {
        /** Натив или модель ещё не готовы (идёт/не начиналась загрузка). */
        NOT_READY,
        /** Скачивание не удалось (сеть, HTTP-ошибка, несовпадение sha256). */
        DOWNLOAD_FAILED,
        /** Процесс (llama-cli/piper/whisper-cli) не уложился в таймаут. */
        TIMEOUT,
        /** Процесс завершился с ошибкой или дал пустой/битый вывод. */
        PROCESS_FAILED,
        /** Вызов требует клиентское окружение (микрофон/динамики), а его нет. */
        CLIENT_ONLY,
        /** Слот занят другим запросом дольше, чем разрешено ждать. */
        BUSY
    }

    private final Reason reason;
    /** Только для NOT_READY: 0..100, либо -1, если прогресс неизвестен. */
    private final int progressPercent;

    public AiLibException(Reason reason, String message) {
        this(reason, message, -1);
    }

    public AiLibException(Reason reason, String message, int progressPercent) {
        super(message);
        this.reason = reason;
        this.progressPercent = progressPercent;
    }

    public Reason getReason() {
        return reason;
    }

    public int getProgressPercent() {
        return progressPercent;
    }
}
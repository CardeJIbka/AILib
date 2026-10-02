package com.cardejibka.ailib.api;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Хэндл зарегистрированной модели: состояние, прогресс, причина сбоя, повтор, удаление.
 * Получается из {@link AiLib#register(ModelSpec)}; регистрация никогда не бросает —
 * проблемы (запрещённый домен, коллизия имени) приходят как состояние FAILED + причина.
 */
public interface ModelHandle {

    enum State { DOWNLOADING, VERIFYING, READY, FAILED }

    enum FailureReason {
        DOMAIN_BLOCKED, FILENAME_COLLISION, NETWORK, HTTP_ERROR, INCOMPLETE, HASH_MISMATCH, UNKNOWN
    }

    ModelSpec spec();

    State state();

    default boolean isReady() {
        return state() == State.READY;
    }

    /** 0..1 для текущего файла модели, либо -1 если неизвестно. */
    float progress();

    /** null, если состояние не FAILED. */
    FailureReason failureReason();

    String failureMessage();

    /** Завершается true/false по итогам текущей попытки. После retry() возвращает новый future. */
    CompletableFuture<Boolean> future();

    /** Путь к основному файлу модели на диске. */
    Path path();

    /** Повторить загрузку, если состояние FAILED. @return true, если повтор запущен. */
    boolean retry();

    /** Удалить файлы и снять регистрацию. Не работает во время загрузки. @return true при успехе. */
    boolean delete();
}

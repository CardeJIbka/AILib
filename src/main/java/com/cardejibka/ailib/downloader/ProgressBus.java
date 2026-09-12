package com.cardejibka.ailib.downloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Простая шина событий прогресса. Common-код (AiLibBootstrap) публикует сюда,
 * не зная, есть ли вообще клиент/HUD — это позволяет одному и тому же коду
 * работать и на dedicated-сервере (просто логи), и на клиенте (HUD поверх логов).
 */
public final class ProgressBus {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Progress");
    private static final List<ProgressSink> SINKS = new CopyOnWriteArrayList<>();

    // Чтобы не спамить лог на каждый прочитанный чанк — логируем только каждые ~10%.
    private static final Map<String, Integer> LAST_LOGGED_PERCENT = new ConcurrentHashMap<>();
    // Последний известный процент по каждому таску — чтобы AiLib мог сразу
    // сказать вызывающему коду "готово на 42%", а не просто "ещё не готово".
    private static final Map<String, Integer> LATEST_PERCENT = new ConcurrentHashMap<>();

    private ProgressBus() {
    }

    public static void subscribe(ProgressSink sink) {
        SINKS.add(sink);
    }

    public static void unsubscribe(ProgressSink sink) {
        SINKS.remove(sink);
    }

    public static void publishProgress(String taskId, String label, long downloaded, long total) {
        if (total > 0) {
            int percent = (int) (downloaded * 100 / total);
            LATEST_PERCENT.put(taskId, percent);
            int lastLogged = LAST_LOGGED_PERCENT.getOrDefault(taskId, -10);
            if (percent - lastLogged >= 10) {
                LAST_LOGGED_PERCENT.put(taskId, percent);
                LOGGER.info("[{}] {}%", label, percent);
            }
        }
        for (ProgressSink sink : SINKS) {
            try {
                sink.onProgress(taskId, label, downloaded, total);
            } catch (Exception e) {
                LOGGER.error("Ошибка в подписчике прогресса", e);
            }
        }
    }

    /** -1, если по этому таску ещё не было ни одного события прогресса. */
    public static int getLastPercent(String taskId) {
        return LATEST_PERCENT.getOrDefault(taskId, -1);
    }

    public static void publishFinished(String taskId, String label, boolean success) {
        LAST_LOGGED_PERCENT.remove(taskId);
        LATEST_PERCENT.remove(taskId);
        LOGGER.info("[{}] {}", label, success ? "готово" : "ошибка");
        for (ProgressSink sink : SINKS) {
            try {
                sink.onFinished(taskId, success);
            } catch (Exception e) {
                LOGGER.error("Ошибка в подписчике прогресса", e);
            }
        }
    }
}
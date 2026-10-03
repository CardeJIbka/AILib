package com.cardejibka.ailib.downloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple progress event bus. Common code (AiLibBootstrap) publishes here without knowing whether a
 * client/HUD exists at all, so the same code works on a dedicated server (log lines only) and on
 * the client (HUD on top of the log).
 */
public final class ProgressBus {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Progress");
    private static final List<ProgressSink> SINKS = new CopyOnWriteArrayList<>();

    // Avoid spamming the log for every chunk: log roughly every 10%.
    private static final Map<String, Integer> LAST_LOGGED_PERCENT = new ConcurrentHashMap<>();
    // Latest known percentage per task, so AiLib can tell callers "42% done" instead of just "not ready".
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
                LOGGER.error("Error in a progress subscriber", e);
            }
        }
    }

    /** -1 if no progress event has been published for this task yet. */
    public static int getLastPercent(String taskId) {
        return LATEST_PERCENT.getOrDefault(taskId, -1);
    }

    public static void publishFinished(String taskId, String label, boolean success) {
        LAST_LOGGED_PERCENT.remove(taskId);
        LATEST_PERCENT.remove(taskId);
        LOGGER.info("[{}] {}", label, success ? "done" : "failed");
        for (ProgressSink sink : SINKS) {
            try {
                sink.onFinished(taskId, success);
            } catch (Exception e) {
                LOGGER.error("Error in a progress subscriber", e);
            }
        }
    }
}

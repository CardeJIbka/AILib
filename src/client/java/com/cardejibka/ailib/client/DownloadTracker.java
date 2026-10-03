package com.cardejibka.ailib.client;

import com.cardejibka.ailib.downloader.ProgressSink;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Several artifacts (LLM/TTS/STT natives and any number of models) download IN PARALLEL, so the
 * tracker keeps several active tasks at once, each with its own progress.
 * <p>
 * Implements {@link ProgressSink}: it subscribes to the common ProgressBus (see AiLibClient)
 * instead of reading the downloader's state directly.
 */
public class DownloadTracker implements ProgressSink {

    public record TaskState(String id, String label, long downloaded, long total, long startedAtMs) {
        /** 0..1, or -1 if the server sent no size (the "unknown" indicator). */
        public float progress() {
            return total > 0 ? (float) downloaded / total : -1f;
        }
    }

    private static final Map<String, TaskState> ACTIVE_TASKS = new ConcurrentHashMap<>();

    @Override
    public void onProgress(String taskId, String label, long downloaded, long total) {
        TaskState existing = ACTIVE_TASKS.get(taskId);
        long startedAt = existing != null ? existing.startedAtMs() : System.currentTimeMillis();
        ACTIVE_TASKS.put(taskId, new TaskState(taskId, label, downloaded, total, startedAt));
    }

    @Override
    public void onFinished(String taskId, boolean success) {
        ACTIVE_TASKS.remove(taskId);
    }

    public static boolean isDownloading() {
        return !ACTIVE_TASKS.isEmpty();
    }

    /** Sorted by start time: older tasks (usually closer to completion) first. */
    public static List<TaskState> getActiveTasks() {
        return ACTIVE_TASKS.values().stream()
                .sorted(Comparator.comparingLong(TaskState::startedAtMs))
                .toList();
    }
}

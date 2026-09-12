package com.cardejibka.ailib.client;

import com.cardejibka.ailib.downloader.ProgressSink;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * В твоей NeoForge-версии это был один global boolean + один currentTask + один
 * progress — то есть в один момент времени можно было показать только одну
 * загрузку. Раз теперь несколько артефактов (натив LLM, натив TTS, натив STT,
 * несколько моделей) качаются ПАРАЛЛЕЛЬНО, трекер должен уметь держать
 * несколько активных задач одновременно, каждую со своим прогрессом.
 * <p>
 * Реализует {@link ProgressSink} — подписывается на общую {@code ProgressBus}
 * из common-кода (см. AiLibClient), а не читает состояние загрузчика напрямую.
 */
public class DownloadTracker implements ProgressSink {

    public record TaskState(String id, String label, long downloaded, long total, long startedAtMs) {
        /** 0..1, либо -1 если сервер не прислал размер (индикатор "неизвестно"). */
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

    /** Отсортировано по времени начала — старые (более близкие к завершению обычно) сверху. */
    public static List<TaskState> getActiveTasks() {
        return ACTIVE_TASKS.values().stream()
                .sorted(Comparator.comparingLong(TaskState::startedAtMs))
                .toList();
    }
}
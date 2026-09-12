package com.cardejibka.ailib.downloader;

/**
 * Подписчик на прогресс скачивания. Реализуется как минимум логгером (всегда),
 * а на клиенте ещё и {@code DownloadTracker}, который прокидывает данные в HUD.
 * Специально не завязано на Minecraft/GuiGraphics — этот интерфейс живёт в common-коде.
 */
public interface ProgressSink {
    /** total == -1, если сервер не прислал Content-Length (индикатор "неизвестно сколько"). */
    void onProgress(String taskId, String label, long downloaded, long total);

    default void onFinished(String taskId, boolean success) {
    }
}
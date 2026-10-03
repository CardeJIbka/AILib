package com.cardejibka.ailib.downloader;

/**
 * Subscriber to download progress. Implemented at least by the logger (always) and, on the client,
 * by {@code DownloadTracker}, which feeds the HUD. Deliberately free of Minecraft/GuiGraphics
 * references: this interface lives in common code.
 */
public interface ProgressSink {
    /** total == -1 if the server sent no Content-Length (the "unknown size" indicator). */
    void onProgress(String taskId, String label, long downloaded, long total);

    default void onFinished(String taskId, boolean success) {
    }
}

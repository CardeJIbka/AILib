package com.cardejibka.ailib;

import com.cardejibka.ailib.config.AiLibConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Раньше всё тяжёлое (скачивание, запуск llama-cli/piper/whisper-cli) шло через
 * {@code CompletableFuture.runAsync(...)} на общий {@code ForkJoinPool.commonPool()}.
 * <p>
 * Здесь два разных пула с разным смыслом:
 * <ul>
 *   <li>{@link #DOWNLOAD_EXECUTOR} — параллельные скачивания (натив A, натив B,
 *       модель C — одновременно, но не бесконечным числом потоков, чтобы не забить
 *       канал и диск игрока). Размер пула — из config/ailib.json (maxParallelDownloads).</li>
 *   <li>{@link #PROCESS_IO_EXECUTOR} — чтение stdout/stderr запущенных нативных
 *       процессов (лёгкая работа, отдельно от скачиваний).</li>
 * </ul>
 * Плюс по одному "слоту" на каждый нативный движок — вызовы одного и того же
 * движка выполняются по очереди (fair-режим — без обгонов между модами),
 * а не параллельно, чтобы не поднимать несколько тяжёлых моделей в памяти разом.
 */
public final class AiLibExecutors {

    public static final ExecutorService DOWNLOAD_EXECUTOR =
            Executors.newFixedThreadPool(
                    Math.max(1, AiLibConfig.get().maxParallelDownloads),
                    namedThreadFactory("AiLib-Download"));

    public static final ExecutorService PROCESS_IO_EXECUTOR =
            Executors.newFixedThreadPool(4, namedThreadFactory("AiLib-ProcessIO"));

    public static final Semaphore LLM_SLOT = new Semaphore(1, true);
    public static final Semaphore TTS_SLOT = new Semaphore(1, true);
    public static final Semaphore STT_SLOT = new Semaphore(1, true);

    private AiLibExecutors() {
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static void shutdown() {
        DOWNLOAD_EXECUTOR.shutdownNow();
        PROCESS_IO_EXECUTOR.shutdownNow();
    }
}
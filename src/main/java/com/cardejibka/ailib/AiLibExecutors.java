package com.cardejibka.ailib;

import com.cardejibka.ailib.config.AiLibConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Три пула с разным смыслом:
 * <ul>
 *   <li>{@link #DOWNLOAD_EXECUTOR} — параллельные скачивания (размер из конфига).</li>
 *   <li>{@link #PROCESS_IO_EXECUTOR} — ТОЛЬКО чтение stdout нативных процессов. Раньше туда же
 *       попадали тела команд, блокирующиеся на семафоре, и читатели голодали.</li>
 *   <li>{@link #CALL_EXECUTOR} — вызовы API/команд (async-методы AiLib, /ailib ...).
 *       Ограниченная очередь: при перегрузке задачи отклоняются, а не копятся бесконечно.</li>
 * </ul>
 * Плюс по одному fair-слоту на нативный движок — вызовы одного движка идут по очереди.
 */
public final class AiLibExecutors {

    public static final ExecutorService DOWNLOAD_EXECUTOR =
            Executors.newFixedThreadPool(
                    Math.max(1, AiLibConfig.get().maxParallelDownloads),
                    namedThreadFactory("AiLib-Download"));

    public static final ExecutorService PROCESS_IO_EXECUTOR =
            Executors.newFixedThreadPool(4, namedThreadFactory("AiLib-ProcessIO"));

    public static final ExecutorService CALL_EXECUTOR = createCallExecutor();

    public static final Semaphore LLM_SLOT = new Semaphore(1, true);
    public static final Semaphore TTS_SLOT = new Semaphore(1, true);
    public static final Semaphore STT_SLOT = new Semaphore(1, true);

    private AiLibExecutors() {
    }

    private static ExecutorService createCallExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(8, 8, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128), namedThreadFactory("AiLib-Call"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
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
        CALL_EXECUTOR.shutdownNow();
    }
}

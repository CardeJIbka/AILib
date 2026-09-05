package com.cardejibka.ailib;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class AiLibExecutors {

    public static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(
            4, namedThreadFactory("AiLib-IO"));

    public static final Semaphore LLM_SLOT = new Semaphore(1);
    public static final Semaphore TTS_SLOT = new Semaphore(1);
    public static final Semaphore STT_SLOT = new Semaphore(1);

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
        IO_EXECUTOR.shutdownNow();
    }
}
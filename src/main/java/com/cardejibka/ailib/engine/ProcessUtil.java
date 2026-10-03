package com.cardejibka.ailib.engine;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class ProcessUtil {
    private ProcessUtil() {
    }

    static String relativeOrAbsolute(Path base, Path target) {
        try {
            return base.toAbsolutePath().relativize(target.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return target.toAbsolutePath().toString();
        }
    }

    /** Kills the process and waits for it to exit so the reader threads get EOF. */
    static void killAndWait(Process process) {
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

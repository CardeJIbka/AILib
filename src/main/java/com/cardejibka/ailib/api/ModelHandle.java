package com.cardejibka.ailib.api;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Handle of a registered model: state, progress, failure reason, retry, delete.
 * Obtained from {@link AiLib#register(ModelSpec)}. Registration never throws: problems
 * (blocked domain, file-name collision) are reported as state FAILED plus a reason.
 */
public interface ModelHandle {

    enum State { DOWNLOADING, VERIFYING, READY, FAILED }

    enum FailureReason {
        DOMAIN_BLOCKED, FILENAME_COLLISION, NETWORK, HTTP_ERROR, INCOMPLETE, HASH_MISMATCH, UNKNOWN
    }

    ModelSpec spec();

    State state();

    default boolean isReady() {
        return state() == State.READY;
    }

    /** 0..1 for the file currently being downloaded, or -1 if unknown. */
    float progress();

    /** null unless the state is FAILED. */
    FailureReason failureReason();

    String failureMessage();

    /** Completes with true/false when the current attempt finishes. After retry() a new future is returned. */
    CompletableFuture<Boolean> future();

    /** Path of the main model file on disk. */
    Path path();

    /** Retries the download if the state is FAILED. @return true if a retry was started */
    boolean retry();

    /** Deletes the files and unregisters the model. Not possible while downloading. @return true on success */
    boolean delete();
}

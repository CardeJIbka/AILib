package com.cardejibka.ailib.api;

public class AiLibException extends RuntimeException {

    public enum Reason {
        /** The native binary or the model is not ready yet (download in progress or not started). */
        NOT_READY,
        /** A download failed (network, HTTP error, sha256 mismatch). */
        DOWNLOAD_FAILED,
        /** The process (llama-cli/piper/whisper-cli) exceeded its timeout. */
        TIMEOUT,
        /** The process exited with an error or produced empty/garbage output. */
        PROCESS_FAILED,
        /** The call needs a client environment (microphone/speakers) that is not available. */
        CLIENT_ONLY,
        /** The engine slot has been busy with another request for longer than we are willing to wait. */
        BUSY,
        /** There is no prebuilt native binary for this OS/architecture. */
        UNSUPPORTED_PLATFORM
    }

    private final Reason reason;
    /** NOT_READY only: 0..100, or -1 if the progress is unknown. */
    private final int progressPercent;

    public AiLibException(Reason reason, String message) {
        this(reason, message, -1);
    }

    public AiLibException(Reason reason, String message, int progressPercent) {
        super(message);
        this.reason = reason;
        this.progressPercent = progressPercent;
    }

    public Reason getReason() {
        return reason;
    }

    public int getProgressPercent() {
        return progressPercent;
    }
}

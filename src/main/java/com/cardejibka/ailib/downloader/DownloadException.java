package com.cardejibka.ailib.downloader;

public class DownloadException extends Exception {

    public enum Kind { NETWORK, HTTP_ERROR, INCOMPLETE, HASH_MISMATCH }

    private final Kind kind;

    public DownloadException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public DownloadException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}

package com.jejari.queue.domain;

public class QueueException extends RuntimeException {
    private final ErrorCode code;

    public QueueException(ErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public ErrorCode code() { return code; }
}

package com.jejari.queue.api;

import com.jejari.queue.domain.ErrorCode;
import java.util.UUID;

/** 기능설계서 7장 실패 응답: { "code", "message", "traceId" } */
public record ErrorResponse(String code, String message, String traceId) {
    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.message(), UUID.randomUUID().toString());
    }
}

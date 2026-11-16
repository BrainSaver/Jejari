package com.jejari.queue.api;

import com.jejari.queue.domain.QueueException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(QueueException.class)
    public ResponseEntity<ErrorResponse> handle(QueueException e) {
        return ResponseEntity.status(e.code().status()).body(ErrorResponse.of(e.code()));
    }
}

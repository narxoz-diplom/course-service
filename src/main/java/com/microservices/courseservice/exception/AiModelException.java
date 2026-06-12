package com.microservices.courseservice.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AiModelException extends RuntimeException {

    public enum Code {
        INVALID_MODEL_ID,
        MODEL_NOT_ALLOWED,
        MODEL_DISABLED,
        QUOTA_EXCEEDED
    }

    private final Code code;
    private final HttpStatus status;

    public AiModelException(Code code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
}

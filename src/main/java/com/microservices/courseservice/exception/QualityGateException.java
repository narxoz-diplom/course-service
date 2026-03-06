package com.microservices.courseservice.exception;

/**
 * Thrown when lessons or test questions fail quality-gate validation
 * (min length, non-empty fields, valid order, or deduplication).
 */
public class QualityGateException extends RuntimeException {

    public QualityGateException(String message) {
        super(message);
    }

    public QualityGateException(String message, Throwable cause) {
        super(message, cause);
    }
}

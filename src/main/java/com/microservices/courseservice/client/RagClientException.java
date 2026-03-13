package com.microservices.courseservice.client;

public class RagClientException extends RuntimeException {

    public RagClientException(String message) {
        super(message);
    }

    public RagClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

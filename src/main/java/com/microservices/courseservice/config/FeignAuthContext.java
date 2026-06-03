package com.microservices.courseservice.config;

public final class FeignAuthContext {

    private static final ThreadLocal<String> HEADER = new ThreadLocal<>();

    private FeignAuthContext() {
    }

    public static void setAuthorization(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            HEADER.remove();
        } else {
            HEADER.set(authorization);
        }
    }

    public static String getAuthorization() {
        return HEADER.get();
    }

    public static void clear() {
        HEADER.remove();
    }
}

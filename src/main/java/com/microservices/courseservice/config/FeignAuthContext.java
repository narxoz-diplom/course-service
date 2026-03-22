package com.microservices.courseservice.config;

/**
 * Bearer (full {@code Authorization} header value) for outbound Feign calls when there is no
 * servlet request (e.g. {@code @Async} after the HTTP thread ended).
 */
public final class FeignAuthContext {

    private static final ThreadLocal<String> HEADER = new ThreadLocal<>();

    private FeignAuthContext() {
    }

    /** Value should be the raw header, e.g. {@code Bearer <jwt>}. */
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

package com.acme.trade.application.exception;

public final class RateLimitExceededException extends RuntimeException {
    private static final long FALLBACK_RETRY_AFTER_SECONDS = 60;

    private final String code;
    private final long retryAfterSeconds;

    public RateLimitExceededException() {
        this(0);
    }

    public RateLimitExceededException(long retryAfterMilliseconds) {
        this("LOGIN_RATE_LIMITED", "too many authentication attempts; retry later", retryAfterMilliseconds);
    }

    public RateLimitExceededException(String code, String message, long retryAfterMilliseconds) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("rate-limit code is required");
        }
        this.code = code;
        retryAfterSeconds = retryAfterMilliseconds > 0
                ? Math.max(1, retryAfterMilliseconds / 1000 + (retryAfterMilliseconds % 1000 == 0 ? 0 : 1))
                : FALLBACK_RETRY_AFTER_SECONDS;
    }

    public String code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

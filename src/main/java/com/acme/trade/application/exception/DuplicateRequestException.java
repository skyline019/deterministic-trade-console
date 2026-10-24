package com.acme.trade.application.exception;

/** Infrastructure translates the database unique-key violation into this framework-free signal. */
public final class DuplicateRequestException extends RuntimeException {
    private final String requestId;

    public DuplicateRequestException(String requestId, Throwable cause) {
        super("duplicate request id: " + requestId, cause);
        this.requestId = requestId;
    }

    public String requestId() {
        return requestId;
    }
}

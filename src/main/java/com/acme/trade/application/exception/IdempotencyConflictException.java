package com.acme.trade.application.exception;

public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String requestId) {
        super("Idempotency-Key was already used with a different payload: " + requestId);
    }
}

package com.acme.trade.application.exception;

public final class RequestInProgressException extends RuntimeException {
    public RequestInProgressException(String requestId) {
        super("an order with the same Idempotency-Key is in progress: " + requestId);
    }
}

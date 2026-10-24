package com.acme.trade.domain.exception;

public final class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}

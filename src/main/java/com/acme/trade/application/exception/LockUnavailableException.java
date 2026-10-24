package com.acme.trade.application.exception;

public final class LockUnavailableException extends RuntimeException {
    public LockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public LockUnavailableException(String message) {
        super(message);
    }
}

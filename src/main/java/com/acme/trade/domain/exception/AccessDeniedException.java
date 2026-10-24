package com.acme.trade.domain.exception;

public final class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String permission) {
        super("missing required permission: " + permission);
    }
}

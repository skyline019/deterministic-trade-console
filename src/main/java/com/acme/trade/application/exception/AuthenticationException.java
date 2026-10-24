package com.acme.trade.application.exception;

/** Generic by design: callers cannot distinguish unknown users, disabled users and bad passwords. */
public final class AuthenticationException extends RuntimeException {
    public AuthenticationException() {
        super("invalid or expired credentials");
    }
}

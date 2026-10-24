package com.acme.trade.application.port;

public interface AccessTokenGenerator {
    /** Returns a new raw 256-bit token; only its SHA-256 hash may be persisted. */
    String newToken();
}

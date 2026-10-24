package com.acme.trade.application.result;

import com.acme.trade.domain.security.Actor;

import java.time.Instant;
import java.util.Objects;

/** Raw token exists only in this return value and the HTTP response, never in persistence. */
public record IssuedToken(String rawToken, Instant expiresAt, Actor actor) {
    public IssuedToken {
        Objects.requireNonNull(rawToken, "rawToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(actor, "actor");
    }
}

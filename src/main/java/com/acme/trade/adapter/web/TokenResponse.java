package com.acme.trade.adapter.web;

import com.acme.trade.application.result.IssuedToken;

import java.time.Instant;

/**
 * Bearer responses include {@code accessToken}. Cookie delivery omits it (null) so XSS cannot
 * read the session secret from JSON; the HttpOnly {@code __Host-TradeSession} carries it.
 */
public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, ActorResponse actor) {
    public static TokenResponse bearer(IssuedToken token) {
        return new TokenResponse(token.rawToken(), "Bearer", token.expiresAt(), ActorResponse.from(token.actor()));
    }

    public static TokenResponse cookie(IssuedToken token) {
        return new TokenResponse(null, "Cookie", token.expiresAt(), ActorResponse.from(token.actor()));
    }

    @Override
    public String toString() {
        return "TokenResponse[accessToken=REDACTED,tokenType=" + tokenType + "]";
    }
}

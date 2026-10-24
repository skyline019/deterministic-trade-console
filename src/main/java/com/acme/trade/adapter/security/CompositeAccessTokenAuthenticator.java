package com.acme.trade.adapter.security;

import com.acme.trade.application.port.AccessTokenAuthenticator;
import com.acme.trade.domain.security.Actor;

import java.util.Objects;
import java.util.Optional;

/**
 * Routes JWT-shaped credentials to optional JWT AuthN; otherwise opaque DB tokens.
 */
public final class CompositeAccessTokenAuthenticator implements AccessTokenAuthenticator {
    private final AccessTokenAuthenticator opaque;
    private final Optional<AccessTokenAuthenticator> jwt;

    public CompositeAccessTokenAuthenticator(AccessTokenAuthenticator opaque,
                                             Optional<AccessTokenAuthenticator> jwt) {
        this.opaque = Objects.requireNonNull(opaque, "opaque");
        this.jwt = Objects.requireNonNull(jwt, "jwt");
    }

    @Override
    public Actor authenticate(String rawToken) {
        if (jwt.isPresent() && JwtTokenShape.looksLikeJwt(rawToken)) {
            return jwt.orElseThrow().authenticate(rawToken);
        }
        return opaque.authenticate(rawToken);
    }
}

package com.acme.trade.application.port;

import com.acme.trade.domain.security.Actor;

/**
 * AuthN port: raw credential → Actor. Implementations may be opaque DB tokens or external JWT.
 * Authorization (Permission checks) stays in Application services.
 */
public interface AccessTokenAuthenticator {
    Actor authenticate(String rawToken);
}

package com.acme.trade.application.port;

import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.UserAccount;

import java.time.Instant;
import java.util.Optional;

public interface ApiTokenRepository {
    void insert(String tokenHash, UserAccount user, Instant createdAt, Instant expiresAt);

    Optional<Actor> findActiveActor(String tokenHash, Instant now);

    boolean delete(String tokenHash);
}

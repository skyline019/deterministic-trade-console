package com.acme.trade.application.service;

import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.application.port.AccessTokenAuthenticator;
import com.acme.trade.application.port.AccessTokenGenerator;
import com.acme.trade.application.port.ApiTokenRepository;
import com.acme.trade.application.port.LoginThrottle;
import com.acme.trade.application.port.PasswordCodec;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.port.UserRepository;
import com.acme.trade.application.result.IssuedToken;
import com.acme.trade.domain.security.AccessTokenHash;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.PasswordPolicy;
import com.acme.trade.domain.security.UserAccount;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class AuthService implements AccessTokenAuthenticator {
    private final UserRepository users;
    private final ApiTokenRepository tokens;
    private final PasswordCodec passwords;
    private final AccessTokenGenerator tokenGenerator;
    private final LoginThrottle throttle;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Duration tokenTtl;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, ApiTokenRepository tokens, PasswordCodec passwords,
                       AccessTokenGenerator tokenGenerator, LoginThrottle throttle,
                       TransactionRunner transactions, Clock clock, Duration tokenTtl,
                       String dummyPasswordHash) {
        this.users = Objects.requireNonNull(users, "users");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
        this.dummyPasswordHash = Objects.requireNonNull(dummyPasswordHash, "dummyPasswordHash");
        if (tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("token TTL must be positive");
        }
    }

    public IssuedToken issue(String username, String password, String clientAddress) {
        String normalized = username == null ? "" : username.toLowerCase(Locale.ROOT);
        String fingerprint = AccessTokenHash.fingerprint(normalized + '\n' + clientAddress);
        throttle.check(fingerprint);

        // Single auto-commit read: BCrypt work never holds a transaction/connection from the pool.
        Optional<UserAccount> user = UserAccount.isValidUsername(normalized)
                ? users.findByUsername(normalized) : Optional.empty();
        String expectedHash = user.map(UserAccount::passwordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = PasswordPolicy.hasSafeLoginSize(password)
                && passwords.matches(password, expectedHash);
        if (user.isEmpty() || !user.orElseThrow().enabled() || !passwordMatches) {
            throw new AuthenticationException();
        }

        UserAccount authenticated = user.orElseThrow();
        String rawToken = tokenGenerator.newToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(tokenTtl);
        // Redis I/O stays outside the DB transaction; a reset failure cannot persist an unreturned token.
        throttle.reset(fingerprint);
        transactions.inTransaction(TransactionMode.WRITE, () -> {
            tokens.insert(AccessTokenHash.of(rawToken), authenticated, now, expiresAt);
            return null;
        });
        return new IssuedToken(rawToken, expiresAt, authenticated.actor());
    }

    @Override
    public Actor authenticate(String rawToken) {
        if (!AccessTokenHash.isValidRawToken(rawToken)) {
            throw new AuthenticationException();
        }
        return tokens.findActiveActor(AccessTokenHash.of(rawToken), clock.instant())
                .orElseThrow(AuthenticationException::new);
    }

    public void revoke(String rawToken) {
        if (!AccessTokenHash.isValidRawToken(rawToken)) {
            throw new AuthenticationException();
        }
        transactions.inTransaction(TransactionMode.WRITE, () -> tokens.delete(AccessTokenHash.of(rawToken)));
    }
}

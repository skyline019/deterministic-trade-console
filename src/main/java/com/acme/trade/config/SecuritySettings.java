package com.acme.trade.config;

import java.time.Duration;
import java.util.Locale;

public record SecuritySettings(
        Duration tokenTtl,
        int bcryptStrength,
        int loginAttemptLimit,
        Duration loginWindow,
        String bootstrapAdminUsername,
        int apiAttemptLimit,
        Duration apiWindow,
        boolean cookieSecure,
        boolean jwtEnabled,
        String jwtMode,
        String jwtHs256Secret,
        String jwtJwksJson,
        boolean passwordLoginEnabled) {
    public SecuritySettings {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()
                || tokenTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("access token TTL must be positive and at most 24h");
        }
        if (bcryptStrength < 10 || bcryptStrength > 14 || loginAttemptLimit < 1) {
            throw new IllegalArgumentException("invalid security settings");
        }
        if (loginWindow == null || loginWindow.isZero() || loginWindow.isNegative()) {
            throw new IllegalArgumentException("login window must be positive");
        }
        if (!com.acme.trade.domain.security.UserAccount.isValidUsername(bootstrapAdminUsername)) {
            throw new IllegalArgumentException("invalid bootstrap admin username");
        }
        if (apiAttemptLimit < 1) {
            throw new IllegalArgumentException("api attempt limit must be at least 1");
        }
        if (apiWindow == null || apiWindow.isZero() || apiWindow.isNegative()) {
            throw new IllegalArgumentException("api window must be positive");
        }
        jwtHs256Secret = jwtHs256Secret == null ? "" : jwtHs256Secret;
        jwtJwksJson = jwtJwksJson == null ? "" : jwtJwksJson;
        jwtMode = jwtMode == null || jwtMode.isBlank() ? "hs256" : jwtMode.trim().toLowerCase(Locale.ROOT);
        if (!jwtMode.equals("hs256") && !jwtMode.equals("jwks")) {
            throw new IllegalArgumentException("trade.security.jwt.mode must be hs256 or jwks");
        }
        if (jwtEnabled) {
            if (jwtMode.equals("hs256") && jwtHs256Secret.length() < 32) {
                throw new IllegalArgumentException("JWT hs256 mode requires secret of at least 32 characters");
            }
            if (jwtMode.equals("jwks") && jwtJwksJson.isBlank()) {
                throw new IllegalArgumentException("JWT jwks mode requires trade.security.jwt.jwks-json");
            }
        }
        if (!passwordLoginEnabled && !jwtEnabled) {
            throw new IllegalArgumentException("password login cannot be disabled without JWT AuthN enabled");
        }
    }
}

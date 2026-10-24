package com.acme.trade.adapter.security;

import com.acme.trade.application.port.AccessTokenGenerator;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SecureAccessTokenGenerator implements AccessTokenGenerator {
    private final SecureRandom random;

    public SecureAccessTokenGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String newToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}

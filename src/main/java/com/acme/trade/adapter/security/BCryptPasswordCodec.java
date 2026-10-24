package com.acme.trade.adapter.security;

import com.acme.trade.application.port.PasswordCodec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Objects;

public final class BCryptPasswordCodec implements PasswordCodec {
    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordCodec(int strength) {
        if (strength < 10 || strength > 14) {
            throw new IllegalArgumentException("BCrypt strength must be between 10 and 14");
        }
        encoder = new BCryptPasswordEncoder(strength);
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedHash) {
        Objects.requireNonNull(rawPassword, "rawPassword");
        return encoder.matches(rawPassword, encodedHash);
    }
}

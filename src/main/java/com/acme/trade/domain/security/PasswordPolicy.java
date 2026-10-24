package com.acme.trade.domain.security;

import com.acme.trade.domain.exception.ValidationException;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() {
    }

    public static void validateForCreation(String password) {
        if (password == null || password.length() < 12 || password.length() > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ValidationException("password must contain 12..128 characters and at most 72 UTF-8 bytes");
        }
    }

    public static boolean hasSafeLoginSize(String password) {
        return password != null && !password.isEmpty() && password.length() <= 128
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}

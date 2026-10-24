package com.acme.trade.domain.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class AccessTokenHash {
    private static final Pattern RAW_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private AccessTokenHash() {
    }

    public static boolean isValidRawToken(String token) {
        return token != null && RAW_TOKEN.matcher(token).matches();
    }

    public static String of(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is mandatory in Java", exception);
        }
    }

    public static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is mandatory in Java", exception);
        }
    }
}

package com.acme.trade.adapter.security.cookie;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Optional Cookie session delivery (not a silent fallback from Bearer).
 * Parses the Cookie header as text — never {@code jakarta.servlet.http.Cookie} —
 * so AR-011 can forbid Servlet Cookie APIs while still allowing {@code __Host-TradeSession}.
 * {@code __Host-} requires Secure + Path=/ + no Domain (TLS terminator in front).
 */
public final class CookieAuthSupport {
    public static final String SESSION_COOKIE = "__Host-TradeSession";
    public static final String XSRF_COOKIE = "TradeXsrf";
    public static final String XSRF_HEADER = "X-XSRF-TOKEN";
    public static final String DELIVERY_COOKIE = "cookie";
    public static final String DELIVERY_BEARER = "bearer";

    private CookieAuthSupport() {
    }

    public static boolean isCookieDelivery(String delivery) {
        return delivery != null && DELIVERY_COOKIE.equalsIgnoreCase(delivery.trim());
    }

    public static Optional<String> read(HttpServletRequest request, String name) {
        Objects.requireNonNull(request, "request");
        return readHeader(request.getHeader("Cookie"), name);
    }

    /** Pure parser for tests and filters; does not depend on the Servlet Cookie type. */
    public static Optional<String> readHeader(String header, String name) {
        Objects.requireNonNull(name, "name");
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        for (String part : header.split(";")) {
            String piece = part.trim();
            int eq = piece.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = piece.substring(0, eq).trim();
            if (name.equals(key)) {
                String value = piece.substring(eq + 1).trim();
                return value.isEmpty() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static String newXsrfToken() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    public static boolean xsrfMatches(HttpServletRequest request) {
        Optional<String> cookie = read(request, XSRF_COOKIE);
        String header = request.getHeader(XSRF_HEADER);
        if (cookie.isEmpty() || header == null || header.isBlank()) {
            return false;
        }
        return constantTimeEquals(cookie.get(), header.trim());
    }

    public static ResponseCookie sessionCookie(String rawToken, Instant expiresAt, boolean secure) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        if (maxAge.isNegative() || maxAge.isZero()) {
            maxAge = Duration.ofSeconds(1);
        }
        return base(SESSION_COOKIE, rawToken, secure)
                .httpOnly(true)
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie xsrfCookie(String xsrf, Instant expiresAt, boolean secure) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        if (maxAge.isNegative() || maxAge.isZero()) {
            maxAge = Duration.ofSeconds(1);
        }
        return base(XSRF_COOKIE, xsrf, secure)
                .httpOnly(false)
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie clearSession(boolean secure) {
        return base(SESSION_COOKIE, "", secure).httpOnly(true).maxAge(Duration.ZERO).build();
    }

    public static ResponseCookie clearXsrf(boolean secure) {
        return base(XSRF_COOKIE, "", secure).httpOnly(false).maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String name, String value, boolean secure) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path("/")
                .sameSite("Strict")
                .secure(secure);
        // __Host- forbids Domain; non-host cookies also omit Domain for least surprise.
        return builder;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length(); i++) {
            diff |= left.charAt(i) ^ right.charAt(i);
        }
        return diff == 0;
    }
}

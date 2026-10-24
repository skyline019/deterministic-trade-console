package com.acme.trade.adapter.security;

/** Shared JWT shape detection for composite AuthN routing (HS256 or RS256/JWKS). */
public final class JwtTokenShape {
    private JwtTokenShape() {
    }

    public static boolean looksLikeJwt(String raw) {
        if (raw == null) {
            return false;
        }
        int first = raw.indexOf('.');
        int second = first < 0 ? -1 : raw.indexOf('.', first + 1);
        return first > 0 && second > first + 1 && raw.indexOf('.', second + 1) < 0;
    }
}

package com.acme.trade.adapter.security;

import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.application.port.AccessTokenAuthenticator;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Optional external-style AuthN: HS256 JWT → Actor. No Spring SecurityContext.
 * Claim map: {@code sub}, {@code preferred_username} (or {@code username}), {@code roles} array, {@code exp}.
 */
public final class Hs256JwtAccessTokenAuthenticator implements AccessTokenAuthenticator {
    private final byte[] secret;
    private final ObjectMapper json;
    private final Clock clock;

    public Hs256JwtAccessTokenAuthenticator(String hs256Secret, ObjectMapper json, Clock clock) {
        Objects.requireNonNull(hs256Secret, "hs256Secret");
        if (hs256Secret.length() < 32) {
            throw new IllegalArgumentException("JWT HS256 secret must be at least 32 characters");
        }
        this.secret = hs256Secret.getBytes(StandardCharsets.UTF_8);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @deprecated Prefer {@link JwtTokenShape#looksLikeJwt(String)} */
    public static boolean looksLikeJwt(String raw) {
        return JwtTokenShape.looksLikeJwt(raw);
    }

    @Override
    public Actor authenticate(String rawToken) {
        if (!JwtTokenShape.looksLikeJwt(rawToken)) {
            throw new AuthenticationException();
        }
        String[] parts = rawToken.split("\\.", 3);
        if (parts.length != 3) {
            throw new AuthenticationException();
        }
        byte[] signingInput = (parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] expectedSig = hmacSha256(signingInput);
        byte[] actualSig;
        try {
            actualSig = Base64.getUrlDecoder().decode(pad(parts[2]));
        } catch (IllegalArgumentException ex) {
            throw new AuthenticationException();
        }
        if (!constantTimeEquals(expectedSig, actualSig)) {
            throw new AuthenticationException();
        }
        try {
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(pad(parts[0])));
            if (!"HS256".equals(header.path("alg").asText())) {
                throw new AuthenticationException();
            }
            JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(pad(parts[1])));
            long exp = payload.path("exp").asLong(0);
            if (exp <= 0 || exp <= clock.instant().getEpochSecond()) {
                throw new AuthenticationException();
            }
            String sub = text(payload, "sub");
            String username = text(payload, "preferred_username");
            if (username.isBlank()) {
                username = text(payload, "username");
            }
            if (sub.isBlank() || username.isBlank()) {
                throw new AuthenticationException();
            }
            Set<Role> roles = EnumSet.noneOf(Role.class);
            JsonNode rolesNode = payload.get("roles");
            if (rolesNode == null || !rolesNode.isArray() || rolesNode.isEmpty()) {
                throw new AuthenticationException();
            }
            for (JsonNode roleNode : rolesNode) {
                roles.add(Role.valueOf(roleNode.asText().trim().toUpperCase(Locale.ROOT)));
            }
            return new Actor(sub, username.toLowerCase(Locale.ROOT), roles);
        } catch (AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AuthenticationException();
        }
    }

    /** Test/fixture helper: mint a compact HS256 JWT. */
    public static String mint(String hs256Secret, String sub, String username, Set<Role> roles,
                              long expEpochSeconds, ObjectMapper json) {
        try {
            String header = b64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            var body = json.createObjectNode();
            body.put("sub", sub);
            body.put("preferred_username", username);
            var arr = body.putArray("roles");
            for (Role role : roles) {
                arr.add(role.name());
            }
            body.put("exp", expEpochSeconds);
            String payload = b64Url(json.writeValueAsBytes(body));
            String signingInput = header + '.' + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hs256Secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = b64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            return signingInput + '.' + sig;
        } catch (Exception ex) {
            throw new IllegalStateException("JWT mint failed", ex);
        }
    }

    private byte[] hmacSha256(byte[] signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signingInput);
        } catch (Exception ex) {
            throw new AuthenticationException();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String pad(String b64) {
        int mod = b64.length() % 4;
        if (mod == 0) {
            return b64;
        }
        return b64 + "====".substring(mod);
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}

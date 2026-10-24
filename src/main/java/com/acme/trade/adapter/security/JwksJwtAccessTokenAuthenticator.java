package com.acme.trade.adapter.security;

import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.application.port.AccessTokenAuthenticator;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Optional external-style AuthN: static JWKS (RS256) → Actor. No Spring SecurityContext / OIDC SDK.
 * Same claim map as {@link Hs256JwtAccessTokenAuthenticator}.
 */
public final class JwksJwtAccessTokenAuthenticator implements AccessTokenAuthenticator {
    private final Map<String, RSAPublicKey> keysByKid;
    private final ObjectMapper json;
    private final Clock clock;

    public JwksJwtAccessTokenAuthenticator(String jwksJson, ObjectMapper json, Clock clock) {
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.keysByKid = parseJwks(Objects.requireNonNull(jwksJson, "jwksJson"), this.json);
        if (keysByKid.isEmpty()) {
            throw new IllegalArgumentException("JWKS must contain at least one RSA key");
        }
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
        try {
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(pad(parts[0])));
            if (!"RS256".equals(header.path("alg").asText())) {
                throw new AuthenticationException();
            }
            String kid = header.path("kid").asText("").trim();
            RSAPublicKey key = keysByKid.get(kid);
            if (key == null) {
                throw new AuthenticationException();
            }
            byte[] signingInput = (parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII);
            byte[] signature;
            try {
                signature = Base64.getUrlDecoder().decode(pad(parts[2]));
            } catch (IllegalArgumentException ex) {
                throw new AuthenticationException();
            }
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(signingInput);
            if (!verifier.verify(signature)) {
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

    /** Test/fixture: mint compact RS256 JWT with {@code kid}. */
    public static String mint(PrivateKey privateKey, String kid, String sub, String username, Set<Role> roles,
                              long expEpochSeconds, ObjectMapper json) {
        try {
            ObjectNode headerNode = json.createObjectNode();
            headerNode.put("alg", "RS256");
            headerNode.put("typ", "JWT");
            headerNode.put("kid", kid);
            String header = b64Url(json.writeValueAsBytes(headerNode));
            ObjectNode body = json.createObjectNode();
            body.put("sub", sub);
            body.put("preferred_username", username);
            var arr = body.putArray("roles");
            for (Role role : roles) {
                arr.add(role.name());
            }
            body.put("exp", expEpochSeconds);
            String payload = b64Url(json.writeValueAsBytes(body));
            String signingInput = header + '.' + payload;
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + '.' + b64Url(signer.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("RS256 JWT mint failed", ex);
        }
    }

    public static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("RSA key generation failed", ex);
        }
    }

    public static String toJwksJson(ObjectMapper json, Map<String, PublicKey> keysByKid) {
        try {
            ObjectNode root = json.createObjectNode();
            var keys = root.putArray("keys");
            for (Map.Entry<String, PublicKey> entry : keysByKid.entrySet()) {
                if (!(entry.getValue() instanceof RSAPublicKey rsa)) {
                    throw new IllegalArgumentException("only RSA public keys are supported");
                }
                ObjectNode jwk = keys.addObject();
                jwk.put("kty", "RSA");
                jwk.put("kid", entry.getKey());
                jwk.put("alg", "RS256");
                jwk.put("use", "sig");
                jwk.put("n", b64Url(toUnsigned(rsa.getModulus())));
                jwk.put("e", b64Url(toUnsigned(rsa.getPublicExponent())));
            }
            return json.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("JWKS encode failed", ex);
        }
    }

    public static PrivateKey privateKeyFromPkcs8(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception ex) {
            throw new IllegalStateException("PKCS8 decode failed", ex);
        }
    }

    private static Map<String, RSAPublicKey> parseJwks(String jwksJson, ObjectMapper json) {
        try {
            JsonNode root = json.readTree(jwksJson);
            JsonNode keys = root.get("keys");
            if (keys == null || !keys.isArray()) {
                throw new IllegalArgumentException("JWKS keys array required");
            }
            Map<String, RSAPublicKey> map = new LinkedHashMap<>();
            for (JsonNode key : keys) {
                if (!"RSA".equals(key.path("kty").asText())) {
                    continue;
                }
                String kid = key.path("kid").asText("").trim();
                if (kid.isBlank()) {
                    throw new IllegalArgumentException("JWKS RSA key requires kid");
                }
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(pad(key.path("n").asText())));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(pad(key.path("e").asText())));
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e));
                map.put(kid, publicKey);
            }
            return Map.copyOf(map);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid JWKS JSON", ex);
        }
    }

    private static byte[] toUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
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
}

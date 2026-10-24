package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Canonical payload hash used to distinguish a retry from key reuse with another payload. */
public record OrderFingerprint(String value) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public OrderFingerprint {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new ValidationException("order fingerprint must be a lowercase SHA-256 digest");
        }
    }

    public static OrderFingerprint of(Sku sku, Quantity quantity) {
        String canonical = "v1\nsku=" + sku.value() + "\nquantity=" + quantity.value();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new OrderFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Every Java runtime must provide SHA-256", exception);
        }
    }
}

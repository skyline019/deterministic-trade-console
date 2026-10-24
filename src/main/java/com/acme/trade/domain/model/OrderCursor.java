package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Opaque keyset cursor; it carries position only, never authorization or hidden state. */
public record OrderCursor(Instant createdAt, OrderId id) {
    public OrderCursor {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(id, "id");
    }

    public String encode() {
        String raw = createdAt.getEpochSecond() + ":" + createdAt.getNano() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    public static OrderCursor parse(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
            String[] parts = raw.split(":", 3);
            return new OrderCursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Integer.parseInt(parts[1])),
                    OrderId.of(parts[2]));
        } catch (RuntimeException exception) {
            throw new ValidationException("invalid order cursor");
        }
    }
}

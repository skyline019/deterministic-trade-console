package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

import java.time.Instant;
import java.util.Objects;

/** Immutable order aggregate with an explicit, atomic RESERVED -> terminal state transition. */
public record Order(
        OrderId id,
        RequestId requestId,
        Sku sku,
        Quantity quantity,
        OrderFingerprint fingerprint,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        long version) {

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || updatedAt.isBefore(createdAt)) {
            throw new ValidationException("order version and timestamps must be monotonic");
        }
        if (status == OrderStatus.RESERVED) {
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(createdAt)) {
                throw new ValidationException("reserved order expiresAt must be after createdAt");
            }
        }
    }

    public static Order reserved(OrderId id, RequestId requestId, Sku sku, Quantity quantity,
                                 Instant createdAt, Instant expiresAt) {
        return new Order(id, requestId, sku, quantity,
                OrderFingerprint.of(sku, quantity), OrderStatus.RESERVED, createdAt, createdAt, expiresAt, 0);
    }

    public boolean hasPayload(Sku candidateSku, Quantity candidateQuantity) {
        return sku.equals(candidateSku)
                && quantity.equals(candidateQuantity)
                && fingerprint.equals(OrderFingerprint.of(candidateSku, candidateQuantity));
    }

    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == OrderStatus.RESERVED && expiresAt != null && !expiresAt.isAfter(now);
    }

    public Order transitionTo(OrderStatus target, Instant at) {
        if (status != OrderStatus.RESERVED || target == OrderStatus.RESERVED) {
            throw new ValidationException("only RESERVED orders can transition to a terminal state");
        }
        return new Order(id, requestId, sku, quantity, fingerprint, target, createdAt, at, expiresAt, version + 1);
    }

    /**
     * Confirm {@code units} against this reservation. Full confirm → CONFIRMED; partial → RESERVED with reduced quantity.
     * Placement {@link #fingerprint} is preserved for place-idempotency replay.
     */
    public Order confirmUnits(Quantity units, Instant at) {
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(at, "at");
        if (status != OrderStatus.RESERVED) {
            throw new ValidationException("only RESERVED orders can be confirmed");
        }
        if (units.value() <= 0 || units.value() > quantity.value()) {
            throw new ValidationException("confirm quantity must be between 1 and remaining reserved quantity");
        }
        if (units.value() == quantity.value()) {
            return transitionTo(OrderStatus.CONFIRMED, at);
        }
        Quantity remaining = new Quantity(quantity.value() - units.value());
        return new Order(id, requestId, sku, remaining, fingerprint, OrderStatus.RESERVED,
                createdAt, at, expiresAt, version + 1);
    }

    /**
     * Change reservation expiry while staying RESERVED. Rejects terminal or already-expired orders.
     * Placement {@link #fingerprint} is preserved.
     */
    public Order rescheduleExpiresAt(Instant newExpiresAt, Instant at) {
        Objects.requireNonNull(newExpiresAt, "newExpiresAt");
        Objects.requireNonNull(at, "at");
        if (status != OrderStatus.RESERVED) {
            throw new ValidationException("only RESERVED orders can be rescheduled");
        }
        if (isExpiredAt(at)) {
            throw new ValidationException("expired reservations cannot be rescheduled; expire or cancel first");
        }
        if (!newExpiresAt.isAfter(at)) {
            throw new ValidationException("new expiresAt must be after now");
        }
        if (!newExpiresAt.isAfter(createdAt)) {
            throw new ValidationException("reserved order expiresAt must be after createdAt");
        }
        return new Order(id, requestId, sku, quantity, fingerprint, OrderStatus.RESERVED,
                createdAt, at, newExpiresAt, version + 1);
    }
}

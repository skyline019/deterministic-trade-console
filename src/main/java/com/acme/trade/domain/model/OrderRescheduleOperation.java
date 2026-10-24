package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

import java.time.Instant;
import java.util.Objects;

/** Idempotency receipt for changing a reservation's expiresAt (ADR 003). */
public record OrderRescheduleOperation(
        RequestId requestId,
        OrderId orderId,
        Instant newExpiresAt,
        String reason,
        String actorId,
        Instant createdAt) {

    public OrderRescheduleOperation {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(newExpiresAt, "newExpiresAt");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(createdAt, "createdAt");
        validate(reason);
    }

    public static void validate(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 200 || !reason.equals(reason.trim())) {
            throw new ValidationException("reason must contain 1..200 trimmed characters");
        }
    }

    public boolean matches(OrderId candidateOrder, Instant candidateExpiresAt, String candidateReason) {
        return orderId.equals(candidateOrder)
                && newExpiresAt.equals(candidateExpiresAt)
                && reason.equals(candidateReason);
    }
}

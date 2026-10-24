package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

import java.time.Instant;
import java.util.Objects;

public record OrderOperation(
        RequestId requestId,
        OrderId orderId,
        OrderStatus targetStatus,
        int effectQuantity,
        String reason,
        String actorId,
        Instant createdAt) {

    public OrderOperation {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(targetStatus, "targetStatus");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(createdAt, "createdAt");
        validate(targetStatus, reason);
        if (effectQuantity < 0) {
            throw new ValidationException("effect quantity cannot be negative");
        }
    }

    public static void validate(OrderStatus target, String reason) {
        if (target == null || target == OrderStatus.RESERVED) {
            throw new ValidationException("order transition target must be CONFIRMED or CANCELLED");
        }
        if (reason == null || reason.isBlank() || reason.length() > 200 || !reason.equals(reason.trim())) {
            throw new ValidationException("reason must contain 1..200 trimmed characters");
        }
    }

    public boolean matches(OrderId candidateOrder, OrderStatus candidateTarget, String candidateReason,
                           int candidateEffectQuantity) {
        if (!orderId.equals(candidateOrder) || targetStatus != candidateTarget || !reason.equals(candidateReason)) {
            return false;
        }
        // Legacy rows (effect_quantity = 0) only match on identity fields.
        if (effectQuantity == 0) {
            return true;
        }
        return effectQuantity == candidateEffectQuantity;
    }
}

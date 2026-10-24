package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

import java.time.Instant;
import java.util.Objects;

/** Idempotency receipt for a stock mutation; resultingStock is the original operation result. */
public record InventoryOperation(
        RequestId requestId,
        Sku sku,
        InventoryOperationType type,
        int delta,
        String reason,
        StockLevel resultingStock,
        String actorId,
        Instant createdAt) {

    public InventoryOperation {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resultingStock, "resultingStock");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(createdAt, "createdAt");
        validate(type, delta, reason);
    }

    public static void validate(InventoryOperationType type, int delta, String reason) {
        if (type == null) {
            throw new ValidationException("inventory operation type is required");
        }
        long absolute = Math.abs((long) delta);
        boolean validDelta = type == InventoryOperationType.CREATE
                ? delta >= 0 && delta <= StockLevel.MAX
                : delta != 0 && absolute <= StockLevel.MAX;
        if (!validDelta) {
            throw new ValidationException("invalid inventory operation delta");
        }
        if (reason == null || reason.isBlank() || reason.length() > 200 || !reason.equals(reason.trim())) {
            throw new ValidationException("reason must contain 1..200 trimmed characters");
        }
    }

    public boolean matches(Sku candidate, InventoryOperationType candidateType, int candidateDelta, String candidateReason) {
        return sku.equals(candidate) && type == candidateType && delta == candidateDelta && reason.equals(candidateReason);
    }
}

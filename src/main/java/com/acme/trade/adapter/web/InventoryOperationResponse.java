package com.acme.trade.adapter.web;

import com.acme.trade.application.result.InventoryOperationResult;

import java.time.Instant;

public record InventoryOperationResponse(
        String requestId, String sku, String operation, int delta, String reason,
        int resultingAvailable, String actorId, Instant createdAt, boolean replayed) {
    public static InventoryOperationResponse from(InventoryOperationResult result) {
        var operation = result.operation();
        return new InventoryOperationResponse(operation.requestId().value(), operation.sku().value(),
                operation.type().name(), operation.delta(), operation.reason(), operation.resultingStock().value(),
                operation.actorId(), operation.createdAt(), result.replayed());
    }
}

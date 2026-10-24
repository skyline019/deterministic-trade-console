package com.acme.trade.application.result;

import com.acme.trade.domain.model.InventoryOperation;

import java.util.Objects;

public record InventoryOperationResult(InventoryOperation operation, boolean replayed) {
    public InventoryOperationResult {
        Objects.requireNonNull(operation, "operation");
    }
}

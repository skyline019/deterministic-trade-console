package com.acme.trade.adapter.web;

import com.acme.trade.domain.model.Inventory;

import java.time.Instant;

public record InventoryResponse(String sku, int available, int reserved, long version, Instant updatedAt) {
    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(inventory.sku().value(), inventory.available().value(),
                inventory.reserved().value(), inventory.version(), inventory.updatedAt());
    }
}

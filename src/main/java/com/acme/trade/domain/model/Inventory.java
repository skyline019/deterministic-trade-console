package com.acme.trade.domain.model;

import java.time.Instant;
import java.util.Objects;

/** available + reserved is bounded; every stock transition preserves explicit accounting. */
public record Inventory(Sku sku, StockLevel available, StockLevel reserved, long version, Instant updatedAt) {
    public Inventory {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || (long) available.value() + reserved.value() > StockLevel.MAX) {
            throw new IllegalArgumentException("invalid inventory version or capacity");
        }
    }

    public Inventory(Sku sku, StockLevel available, long version, Instant updatedAt) {
        this(sku, available, new StockLevel(0), version, updatedAt);
    }

    public Inventory reserve(Quantity quantity, Instant at) {
        return new Inventory(sku, available.subtract(quantity), reserved.apply(quantity.value()), version + 1, at);
    }

    public Inventory release(Quantity quantity, Instant at) {
        return new Inventory(sku, available.apply(quantity.value()), reserved.subtract(quantity), version + 1, at);
    }

    public Inventory confirm(Quantity quantity, Instant at) {
        return new Inventory(sku, available, reserved.subtract(quantity), version + 1, at);
    }
}

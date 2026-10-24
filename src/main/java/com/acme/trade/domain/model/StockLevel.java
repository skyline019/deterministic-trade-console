package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

public record StockLevel(int value) {
    public static final int MAX = 100_000_000;

    public StockLevel {
        if (value < 0 || value > MAX) {
            throw new ValidationException("stock must be between 0 and " + MAX);
        }
    }

    public static StockLevel of(Integer value) {
        if (value == null) {
            throw new ValidationException("stock is required");
        }
        return new StockLevel(value);
    }

    public StockLevel apply(int delta) {
        long changed = (long) value + delta;
        if (changed < 0 || changed > MAX) {
            throw new ValidationException("stock adjustment exceeds the allowed range");
        }
        return new StockLevel((int) changed);
    }

    public StockLevel subtract(Quantity quantity) {
        long remaining = (long) value - quantity.value();
        if (remaining < 0) {
            throw new ValidationException("stock cannot become negative");
        }
        return new StockLevel((int) remaining);
    }
}

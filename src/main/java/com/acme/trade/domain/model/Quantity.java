package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

/** A positive order quantity. The upper bound also limits accidental oversized requests. */
public record Quantity(int value) {
    public static final int MAX = 10_000;

    public Quantity {
        if (value < 1 || value > MAX) {
            throw new ValidationException("quantity must be between 1 and " + MAX);
        }
    }

    public static Quantity of(Integer value) {
        if (value == null) {
            throw new ValidationException("quantity is required");
        }
        return new Quantity(value);
    }
}

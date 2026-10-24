package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

import java.util.UUID;

public record OrderId(UUID value) {
    public OrderId {
        if (value == null) {
            throw new ValidationException("order id is required");
        }
    }

    public static OrderId random() {
        return new OrderId(UUID.randomUUID());
    }

    public static OrderId of(String value) {
        try {
            return new OrderId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ValidationException("order id must be a UUID");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

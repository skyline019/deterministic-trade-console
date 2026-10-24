package com.acme.trade.domain.audit;

import com.acme.trade.domain.exception.ValidationException;

import java.util.Objects;

public record AggregateRef(AggregateType type, String id) {
    public AggregateRef {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new ValidationException("audit aggregate id must contain 1..128 characters");
        }
    }

    public static AggregateRef of(AggregateType type, String id) {
        return new AggregateRef(type, id);
    }
}

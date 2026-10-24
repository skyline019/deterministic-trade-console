package com.acme.trade.application.query;

import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.OrderCursor;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Sku;

import java.util.Objects;
import java.util.Optional;

public record OrderPageQuery(
        Optional<OrderStatus> status,
        Optional<Sku> sku,
        Optional<OrderCursor> cursor,
        int limit) {
    public OrderPageQuery {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(cursor, "cursor");
        if (limit < 1 || limit > 100) {
            throw new ValidationException("limit must be between 1 and 100");
        }
    }

    public static OrderPageQuery firstPage(int limit) {
        return new OrderPageQuery(Optional.empty(), Optional.empty(), Optional.empty(), limit);
    }
}

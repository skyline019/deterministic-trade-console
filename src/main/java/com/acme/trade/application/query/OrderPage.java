package com.acme.trade.application.query;

import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderCursor;

import java.util.List;
import java.util.Optional;

public record OrderPage(List<Order> items, Optional<OrderCursor> nextCursor) {
    public OrderPage {
        items = List.copyOf(items);
        nextCursor = Optional.ofNullable(nextCursor.orElse(null));
    }
}

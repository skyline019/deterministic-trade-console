package com.acme.trade.application.result;

import com.acme.trade.domain.model.Order;

import java.util.Objects;

public record OrderTransitionResult(Order order, boolean replayed) {
    public OrderTransitionResult {
        Objects.requireNonNull(order, "order");
    }
}

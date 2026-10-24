package com.acme.trade.application.result;

import com.acme.trade.domain.model.Order;

import java.util.Objects;

public record PlaceOrderResult(Order order, boolean replayed) {
    public PlaceOrderResult {
        Objects.requireNonNull(order, "order");
    }

    public static PlaceOrderResult created(Order order) {
        return new PlaceOrderResult(order, false);
    }

    public static PlaceOrderResult replayed(Order order) {
        return new PlaceOrderResult(order, true);
    }
}

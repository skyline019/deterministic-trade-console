package com.acme.trade.application.command;

import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.security.Actor;

import java.util.Objects;

public record PlaceOrderCommand(Actor actor, RequestId requestId, Sku sku, Quantity quantity) {
    public PlaceOrderCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(quantity, "quantity");
    }

    public static PlaceOrderCommand of(Actor actor, String requestId, String sku, Integer quantity) {
        return new PlaceOrderCommand(actor, new RequestId(requestId), Sku.of(sku), Quantity.of(quantity));
    }
}

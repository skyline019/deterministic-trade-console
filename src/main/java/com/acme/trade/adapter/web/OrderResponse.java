package com.acme.trade.adapter.web;

import com.acme.trade.domain.model.Order;

import java.time.Instant;

public record OrderResponse(
        String id,
        String requestId,
        String sku,
        int quantity,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        long version) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(order.id().toString(), order.requestId().value(), order.sku().value(),
                order.quantity().value(), order.status().name(), order.createdAt(), order.updatedAt(),
                order.expiresAt(), order.version());
    }
}

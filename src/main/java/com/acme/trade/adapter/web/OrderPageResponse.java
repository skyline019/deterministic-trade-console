package com.acme.trade.adapter.web;

import com.acme.trade.application.query.OrderPage;

import java.util.List;

public record OrderPageResponse(List<OrderResponse> items, String nextCursor) {
    public static OrderPageResponse from(OrderPage page) {
        return new OrderPageResponse(page.items().stream().map(OrderResponse::from).toList(),
                page.nextCursor().map(cursor -> cursor.encode()).orElse(null));
    }
}

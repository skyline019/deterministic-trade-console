package com.acme.trade.application.service;

import com.acme.trade.application.exception.ResourceNotFoundException;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.query.OrderPage;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderCursor;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class OrderQueryService {
    private final OrderRepository orders;
    private final TransactionRunner transactions;

    public OrderQueryService(OrderRepository orders, TransactionRunner transactions) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Order get(Actor actor, OrderId orderId) {
        actor.require(Permission.ORDER_READ);
        return transactions.inTransaction(TransactionMode.READ_ONLY,
                () -> orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("unknown order: " + orderId)));
    }

    public OrderPage list(Actor actor, OrderPageQuery query) {
        actor.require(Permission.ORDER_READ);
        return transactions.inTransaction(TransactionMode.READ_ONLY, () -> {
            List<Order> fetched = orders.findPage(query, query.limit() + 1);
            boolean hasNext = fetched.size() > query.limit();
            List<Order> items = hasNext ? fetched.subList(0, query.limit()) : fetched;
            Optional<OrderCursor> next = hasNext && !items.isEmpty()
                    ? Optional.of(new OrderCursor(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id()))
                    : Optional.empty();
            return new OrderPage(items, next);
        });
    }
}

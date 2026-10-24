package com.acme.trade.application.service;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.InsufficientStockException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.port.AuditRecorder;
import com.acme.trade.application.port.InventoryRepository;
import com.acme.trade.application.port.LockHandle;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.application.port.OrderIdGenerator;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.result.PlaceOrderResult;
import com.acme.trade.application.usecase.PlaceOrderUseCase;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.security.Permission;

import java.util.Map;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Coordinates the use case. No Spring proxy is involved: the transaction and lock boundaries
 * are visible here, and every dependency is supplied through the constructor.
 */
public final class PlaceOrderService implements PlaceOrderUseCase {
    private static final String LOCK_PREFIX = "trade:order-request:";

    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final LockManager locks;
    private final TransactionRunner transactions;
    private final OrderIdGenerator orderIds;
    private final Clock clock;
    private final Duration lockTtl;
    private final Duration reservationTtl;
    private final AuditRecorder audit;

    public PlaceOrderService(
            InventoryRepository inventory,
            OrderRepository orders,
            LockManager locks,
            TransactionRunner transactions,
            OrderIdGenerator orderIds,
            Clock clock,
            Duration lockTtl,
            Duration reservationTtl,
            AuditRecorder audit) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.orderIds = Objects.requireNonNull(orderIds, "orderIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockTtl = Objects.requireNonNull(lockTtl, "lockTtl");
        this.reservationTtl = Objects.requireNonNull(reservationTtl, "reservationTtl");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (lockTtl.isZero() || lockTtl.isNegative()) {
            throw new IllegalArgumentException("lockTtl must be positive");
        }
        if (reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("reservationTtl must be positive");
        }
    }

    @Override
    public PlaceOrderResult place(PlaceOrderCommand command) {
        Objects.requireNonNull(command, "command");
        command.actor().require(Permission.ORDER_WRITE);
        String requestId = command.requestId().value();
        LockHandle lock = locks.tryAcquire(LOCK_PREFIX + requestId, lockTtl)
                .orElseThrow(() -> new RequestInProgressException(requestId));
        try {
            try {
                return transactions.inTransaction(TransactionMode.WRITE, () -> placeInsideTransaction(command));
            } catch (DuplicateRequestException race) {
                // The losing transaction has rolled back its stock update before this read.
                return transactions.inTransaction(TransactionMode.READ_ONLY, () -> replayAfterRace(command, race));
            }
        } finally {
            lock.close();
        }
    }

    private PlaceOrderResult placeInsideTransaction(PlaceOrderCommand command) {
        var existing = orders.findByRequestId(command.requestId());
        if (existing.isPresent()) {
            return PlaceOrderResult.replayed(requireSamePayload(existing.get(), command));
        }

        if (!inventory.tryReserve(command.sku(), command.quantity(), clock.instant())) {
            throw new InsufficientStockException(command.sku());
        }

        Instant now = clock.instant();
        Order order = Order.reserved(orderIds.newId(), command.requestId(),
                command.sku(), command.quantity(), now, now.plus(reservationTtl));
        orders.insert(order);
        audit.record(command.actor(), "ORDER_RESERVED",
                AggregateRef.of(AggregateType.ORDER, order.id().toString()), command.requestId(),
                Map.of("sku", order.sku().value(), "quantity", Integer.toString(order.quantity().value())));
        audit.record(command.actor(), "STOCK_RESERVED",
                AggregateRef.of(AggregateType.INVENTORY, order.sku().value()), command.requestId(),
                Map.of("orderId", order.id().toString(), "quantity", Integer.toString(order.quantity().value()),
                        "direction", "RESERVE"));
        return PlaceOrderResult.created(order);
    }

    private PlaceOrderResult replayAfterRace(PlaceOrderCommand command, DuplicateRequestException race) {
        return orders.findByRequestId(command.requestId())
                .map(order -> PlaceOrderResult.replayed(requireSamePayload(order, command)))
                .orElseThrow(() -> race);
    }

    private Order requireSamePayload(Order order, PlaceOrderCommand command) {
        if (!order.hasPayload(command.sku(), command.quantity())) {
            throw new IdempotencyConflictException(command.requestId().value());
        }
        return order;
    }
}

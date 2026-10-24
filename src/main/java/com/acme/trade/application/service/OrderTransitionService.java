package com.acme.trade.application.service;

import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.exception.ResourceNotFoundException;
import com.acme.trade.application.exception.StockAdjustmentRejectedException;
import com.acme.trade.application.port.AuditRecorder;
import com.acme.trade.application.port.InventoryRepository;
import com.acme.trade.application.port.LockHandle;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.application.port.OrderOperationRepository;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.result.OrderTransitionResult;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.exception.OrderStateConflictException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderOperation;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Confirmation (full or partial) consumes reservation units; cancellation releases all remaining.
 * Partial confirm keeps the order RESERVED with a reduced quantity (ADR 002).
 */
public final class OrderTransitionService {
    private static final String LOCK_PREFIX = "trade:order-transition:";

    private final OrderRepository orders;
    private final OrderOperationRepository operations;
    private final InventoryRepository inventory;
    private final LockManager locks;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Duration lockTtl;
    private final AuditRecorder audit;

    public OrderTransitionService(OrderRepository orders, OrderOperationRepository operations,
                                  InventoryRepository inventory, LockManager locks,
                                  TransactionRunner transactions, Clock clock, Duration lockTtl,
                                  AuditRecorder audit) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockTtl = Objects.requireNonNull(lockTtl, "lockTtl");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Full cancel, or full confirm when target is CONFIRMED. */
    public OrderTransitionResult transition(Actor actor, RequestId requestId, OrderId orderId,
                                            OrderStatus target, String reason) {
        actor.require(Permission.ORDER_WRITE);
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(orderId, "orderId");
        OrderOperation.validate(target, reason);
        if (target == OrderStatus.CONFIRMED) {
            return confirm(actor, requestId, orderId, null, reason);
        }
        return withLock(requestId, () -> {
            try {
                return transactions.inTransaction(TransactionMode.WRITE,
                        () -> cancelInsideTransaction(actor, requestId, orderId, reason));
            } catch (DuplicateRequestException race) {
                return transactions.inTransaction(TransactionMode.READ_ONLY,
                        () -> replayCancel(requestId, orderId, reason, race));
            }
        });
    }

    /**
     * Confirm units. {@code confirmQuantity == null} means all remaining reserved quantity.
     */
    public OrderTransitionResult confirm(Actor actor, RequestId requestId, OrderId orderId,
                                         Quantity confirmQuantity, String reason) {
        actor.require(Permission.ORDER_WRITE);
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(orderId, "orderId");
        OrderOperation.validate(OrderStatus.CONFIRMED, reason);
        if (confirmQuantity != null && confirmQuantity.value() <= 0) {
            throw new ValidationException("confirm quantity must be between 1 and remaining reserved quantity");
        }
        return withLock(requestId, () -> {
            try {
                return transactions.inTransaction(TransactionMode.WRITE,
                        () -> confirmInsideTransaction(actor, requestId, orderId, confirmQuantity, reason));
            } catch (DuplicateRequestException race) {
                return transactions.inTransaction(TransactionMode.READ_ONLY,
                        () -> replayConfirm(requestId, orderId, confirmQuantity, reason, race));
            }
        });
    }

    private OrderTransitionResult withLock(RequestId requestId, Supplier<OrderTransitionResult> body) {
        LockHandle lock = locks.tryAcquire(LOCK_PREFIX + requestId.value(), lockTtl)
                .orElseThrow(() -> new RequestInProgressException(requestId.value()));
        try {
            return body.get();
        } finally {
            lock.close();
        }
    }

    private OrderTransitionResult cancelInsideTransaction(Actor actor, RequestId requestId, OrderId orderId,
                                                          String reason) {
        var receipt = operations.findByRequestId(requestId);
        if (receipt.isPresent()) {
            OrderOperation operation = requireSame(receipt.get(), orderId, OrderStatus.CANCELLED, reason,
                    receipt.get().effectQuantity());
            return new OrderTransitionResult(findOrder(operation.orderId()), true);
        }

        Order current = findOrder(orderId);
        if (current.status() != OrderStatus.RESERVED) {
            throw new OrderStateConflictException("order is already " + current.status());
        }
        Instant now = clock.instant();
        int effect = current.quantity().value();
        OrderOperation operation = new OrderOperation(requestId, orderId, OrderStatus.CANCELLED, effect,
                reason, actor.id(), now);
        operations.insert(operation);
        Order transitioned = current.transitionTo(OrderStatus.CANCELLED, now);
        if (!orders.transitionStatus(orderId, OrderStatus.RESERVED, OrderStatus.CANCELLED, now)) {
            throw new OrderStateConflictException("order changed concurrently");
        }
        if (!inventory.releaseReservation(current.sku(), current.quantity(), now)) {
            throw new StockAdjustmentRejectedException("reservation ledger is inconsistent for order " + orderId);
        }
        audit.record(actor, "ORDER_CANCELLED",
                AggregateRef.of(AggregateType.ORDER, orderId.toString()), requestId,
                Map.of("from", OrderStatus.RESERVED.name(), "to", OrderStatus.CANCELLED.name(),
                        "reason", reason, "quantity", Integer.toString(effect)));
        audit.record(actor, "STOCK_RELEASED",
                AggregateRef.of(AggregateType.INVENTORY, current.sku().value()), requestId,
                Map.of("orderId", orderId.toString(), "quantity", Integer.toString(effect),
                        "direction", OrderStatus.CANCELLED.name()));
        return new OrderTransitionResult(transitioned, false);
    }

    private OrderTransitionResult confirmInsideTransaction(Actor actor, RequestId requestId, OrderId orderId,
                                                           Quantity confirmQuantity, String reason) {
        var receipt = operations.findByRequestId(requestId);
        if (receipt.isPresent()) {
            int expectedEffect = confirmQuantity != null ? confirmQuantity.value() : receipt.get().effectQuantity();
            OrderOperation operation = requireSame(receipt.get(), orderId, OrderStatus.CONFIRMED, reason, expectedEffect);
            return new OrderTransitionResult(findOrder(operation.orderId()), true);
        }

        Order current = findOrder(orderId);
        if (current.status() != OrderStatus.RESERVED) {
            throw new OrderStateConflictException("order is already " + current.status());
        }
        int effect = confirmQuantity == null ? current.quantity().value() : confirmQuantity.value();
        if (effect <= 0 || effect > current.quantity().value()) {
            throw new ValidationException("confirm quantity must be between 1 and remaining reserved quantity");
        }

        Instant now = clock.instant();
        Quantity units = new Quantity(effect);
        Order next = current.confirmUnits(units, now);

        OrderOperation operation = new OrderOperation(requestId, orderId, OrderStatus.CONFIRMED, effect,
                reason, actor.id(), now);
        operations.insert(operation);

        if (next.status() == OrderStatus.CONFIRMED) {
            if (!orders.transitionStatus(orderId, OrderStatus.RESERVED, OrderStatus.CONFIRMED, now)) {
                throw new OrderStateConflictException("order changed concurrently");
            }
        } else if (!orders.reduceReservedQuantity(orderId, current.quantity().value(), next.quantity().value(), now)) {
            throw new OrderStateConflictException("order quantity changed concurrently");
        }

        if (!inventory.confirmReservation(current.sku(), units, now)) {
            throw new StockAdjustmentRejectedException("reservation ledger is inconsistent for order " + orderId);
        }

        String orderAction = next.status() == OrderStatus.CONFIRMED ? "ORDER_CONFIRMED" : "ORDER_PARTIAL_CONFIRMED";
        audit.record(actor, orderAction,
                AggregateRef.of(AggregateType.ORDER, orderId.toString()), requestId,
                Map.of("from", OrderStatus.RESERVED.name(), "to", next.status().name(),
                        "reason", reason, "confirmedQuantity", Integer.toString(effect),
                        "remainingQuantity", Integer.toString(
                                next.status() == OrderStatus.CONFIRMED ? 0 : next.quantity().value())));
        audit.record(actor, "STOCK_CONFIRMED",
                AggregateRef.of(AggregateType.INVENTORY, current.sku().value()), requestId,
                Map.of("orderId", orderId.toString(), "quantity", Integer.toString(effect),
                        "direction", OrderStatus.CONFIRMED.name()));
        return new OrderTransitionResult(next, false);
    }

    private OrderTransitionResult replayCancel(RequestId requestId, OrderId orderId, String reason,
                                               DuplicateRequestException race) {
        return operations.findByRequestId(requestId)
                .map(op -> new OrderTransitionResult(
                        findOrder(requireSame(op, orderId, OrderStatus.CANCELLED, reason, op.effectQuantity()).orderId()),
                        true))
                .orElseThrow(() -> race);
    }

    private OrderTransitionResult replayConfirm(RequestId requestId, OrderId orderId, Quantity confirmQuantity,
                                                String reason, DuplicateRequestException race) {
        return operations.findByRequestId(requestId)
                .map(op -> {
                    int expected = confirmQuantity != null ? confirmQuantity.value() : op.effectQuantity();
                    return new OrderTransitionResult(
                            findOrder(requireSame(op, orderId, OrderStatus.CONFIRMED, reason, expected).orderId()),
                            true);
                })
                .orElseThrow(() -> race);
    }

    private Order findOrder(OrderId orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("unknown order: " + orderId));
    }

    private OrderOperation requireSame(OrderOperation operation, OrderId orderId, OrderStatus target,
                                       String reason, int effectQuantity) {
        if (!operation.matches(orderId, target, reason, effectQuantity)) {
            throw new IdempotencyConflictException(operation.requestId().value());
        }
        return operation;
    }
}

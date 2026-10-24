package com.acme.trade.application.service;

import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.exception.ResourceNotFoundException;
import com.acme.trade.application.port.AuditRecorder;
import com.acme.trade.application.port.LockHandle;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.port.OrderRescheduleOperationRepository;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.result.OrderTransitionResult;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.exception.OrderStateConflictException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderRescheduleOperation;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Change expiresAt on a live RESERVED order within the reservation TTL window (ADR 003). */
public final class OrderRescheduleService {
    private static final String LOCK_PREFIX = "trade:order-reschedule:";

    private final OrderRepository orders;
    private final OrderRescheduleOperationRepository operations;
    private final LockManager locks;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Duration lockTtl;
    private final Duration reservationTtl;
    private final AuditRecorder audit;

    public OrderRescheduleService(OrderRepository orders, OrderRescheduleOperationRepository operations,
                                  LockManager locks, TransactionRunner transactions, Clock clock,
                                  Duration lockTtl, Duration reservationTtl, AuditRecorder audit) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockTtl = Objects.requireNonNull(lockTtl, "lockTtl");
        this.reservationTtl = Objects.requireNonNull(reservationTtl, "reservationTtl");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("reservationTtl must be positive");
        }
    }

    public OrderTransitionResult reschedule(Actor actor, RequestId requestId, OrderId orderId,
                                            Instant newExpiresAt, String reason) {
        actor.require(Permission.ORDER_WRITE);
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(newExpiresAt, "newExpiresAt");
        OrderRescheduleOperation.validate(reason);
        Instant now = clock.instant();
        if (!newExpiresAt.isAfter(now)) {
            throw new ValidationException("new expiresAt must be after now");
        }
        Instant maxExpiry = now.plus(reservationTtl);
        if (newExpiresAt.isAfter(maxExpiry)) {
            throw new ValidationException("new expiresAt must be within reservation TTL from now");
        }
        return withLock(requestId, () -> {
            try {
                return transactions.inTransaction(TransactionMode.WRITE,
                        () -> rescheduleInside(actor, requestId, orderId, newExpiresAt, reason));
            } catch (DuplicateRequestException race) {
                return transactions.inTransaction(TransactionMode.READ_ONLY,
                        () -> replay(requestId, orderId, newExpiresAt, reason, race));
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

    private OrderTransitionResult rescheduleInside(Actor actor, RequestId requestId, OrderId orderId,
                                                   Instant newExpiresAt, String reason) {
        var receipt = operations.findByRequestId(requestId);
        if (receipt.isPresent()) {
            OrderRescheduleOperation operation = requireSame(receipt.get(), orderId, newExpiresAt, reason);
            return new OrderTransitionResult(findOrder(operation.orderId()), true);
        }

        Order current = findOrder(orderId);
        if (current.status() != OrderStatus.RESERVED) {
            throw new OrderStateConflictException("order is already " + current.status());
        }
        Instant now = clock.instant();
        Order next = current.rescheduleExpiresAt(newExpiresAt, now);

        OrderRescheduleOperation operation = new OrderRescheduleOperation(
                requestId, orderId, newExpiresAt, reason, actor.id(), now);
        operations.insert(operation);

        if (!orders.updateExpiresAt(orderId, current.expiresAt(), newExpiresAt, now)) {
            throw new OrderStateConflictException("order expiry changed concurrently");
        }

        audit.record(actor, "ORDER_RESCHEDULED",
                AggregateRef.of(AggregateType.ORDER, orderId.toString()), requestId,
                Map.of("previousExpiresAt", current.expiresAt().toString(),
                        "expiresAt", newExpiresAt.toString(),
                        "reason", reason));
        return new OrderTransitionResult(next, false);
    }

    private OrderTransitionResult replay(RequestId requestId, OrderId orderId, Instant newExpiresAt,
                                         String reason, DuplicateRequestException race) {
        return operations.findByRequestId(requestId)
                .map(op -> new OrderTransitionResult(
                        findOrder(requireSame(op, orderId, newExpiresAt, reason).orderId()), true))
                .orElseThrow(() -> race);
    }

    private Order findOrder(OrderId orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("unknown order: " + orderId));
    }

    private OrderRescheduleOperation requireSame(OrderRescheduleOperation operation, OrderId orderId,
                                                 Instant newExpiresAt, String reason) {
        if (!operation.matches(orderId, newExpiresAt, reason)) {
            throw new IdempotencyConflictException(operation.requestId().value());
        }
        return operation;
    }
}

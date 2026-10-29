package com.acme.trade.application.service;

import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.exception.ResourceConflictException;
import com.acme.trade.application.exception.ResourceNotFoundException;
import com.acme.trade.application.exception.StockAdjustmentRejectedException;
import com.acme.trade.application.port.AuditRecorder;
import com.acme.trade.application.port.InventoryOperationRepository;
import com.acme.trade.application.port.InventoryRepository;
import com.acme.trade.application.port.LockHandle;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.result.InventoryOperationResult;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.model.Inventory;
import com.acme.trade.domain.model.InventoryOperation;
import com.acme.trade.domain.model.InventoryOperationType;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InventoryService {
    private static final String LOCK_PREFIX = "trade:inventory-request:";

    private final InventoryRepository inventory;
    private final InventoryOperationRepository operations;
    private final LockManager locks;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Duration lockTtl;
    private final AuditRecorder audit;

    public InventoryService(InventoryRepository inventory, InventoryOperationRepository operations,
                            LockManager locks, TransactionRunner transactions, Clock clock,
                            Duration lockTtl, AuditRecorder audit) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockTtl = Objects.requireNonNull(lockTtl, "lockTtl");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public InventoryOperationResult create(Actor actor, RequestId requestId, Sku sku,
                                           StockLevel initialStock, String reason) {
        return execute(actor, requestId, sku, InventoryOperationType.CREATE, initialStock.value(), reason);
    }

    public InventoryOperationResult adjust(Actor actor, RequestId requestId, Sku sku, int delta, String reason) {
        return execute(actor, requestId, sku, InventoryOperationType.ADJUST, delta, reason);
    }

    private InventoryOperationResult execute(Actor actor, RequestId requestId, Sku sku,
                                             InventoryOperationType type, int delta, String reason) {
        actor.require(Permission.INVENTORY_WRITE);
        Objects.requireNonNull(requestId, "requestId");
        InventoryOperation.validate(type, delta, reason);
        LockHandle lock = locks.tryAcquire(LOCK_PREFIX + requestId.value(), lockTtl)
                .orElseThrow(() -> new RequestInProgressException(requestId.value()));
        try {
            try {
                return transactions.inTransaction(TransactionMode.WRITE,
                        () -> executeInsideTransaction(actor, requestId, sku, type, delta, reason));
            } catch (DuplicateRequestException race) {
                return transactions.inTransaction(TransactionMode.READ_ONLY,
                        () -> replayAfterRace(requestId, sku, type, delta, reason, race));
            }
        } finally {
            lock.close();
        }
    }

    private InventoryOperationResult executeInsideTransaction(Actor actor, RequestId requestId, Sku sku,
                                                               InventoryOperationType type, int delta, String reason) {
        var existing = operations.findByRequestId(requestId);
        if (existing.isPresent()) {
            return new InventoryOperationResult(requireSame(existing.get(), sku, type, delta, reason), true);
        }
        boolean accepted = type == InventoryOperationType.CREATE
                ? inventory.create(sku, new StockLevel(delta), clock.instant())
                : inventory.adjust(sku, delta, clock.instant());
        if (!accepted) {
            if (type == InventoryOperationType.CREATE) {
                throw new ResourceConflictException("sku already exists: " + sku.value());
            }
            // Contract (pinned by jtest + mirrored by the demo gateway): a rejected adjustment is one
            // 409 STOCK_ADJUSTMENT_REJECTED whether the row is missing or the bounds are violated.
            // Splitting it into 404 would be a public API change across adapter, demo and docs.
            throw new StockAdjustmentRejectedException("unknown sku or out-of-bounds adjustment: " + sku.value());
        }
        Inventory current = inventory.find(sku).orElseThrow();
        InventoryOperation operation = new InventoryOperation(requestId, sku, type, delta, reason,
                current.available(), actor.id(), clock.instant());
        operations.insert(operation);
        audit.record(actor, type == InventoryOperationType.CREATE ? "STOCK_CREATED" : "STOCK_ADJUSTED",
                AggregateRef.of(AggregateType.INVENTORY, sku.value()), requestId,
                Map.of("delta", Integer.toString(delta), "reason", reason,
                        "resultingAvailable", Integer.toString(current.available().value()),
                        "reserved", Integer.toString(current.reserved().value())));
        return new InventoryOperationResult(operation, false);
    }

    private InventoryOperationResult replayAfterRace(RequestId requestId, Sku sku, InventoryOperationType type,
                                                      int delta, String reason, DuplicateRequestException race) {
        return operations.findByRequestId(requestId)
                .map(operation -> new InventoryOperationResult(requireSame(operation, sku, type, delta, reason), true))
                .orElseThrow(() -> race);
    }

    private InventoryOperation requireSame(InventoryOperation operation, Sku sku,
                                           InventoryOperationType type, int delta, String reason) {
        if (!operation.matches(sku, type, delta, reason)) {
            throw new IdempotencyConflictException(operation.requestId().value());
        }
        return operation;
    }

    public Inventory get(Actor actor, Sku sku) {
        actor.require(Permission.INVENTORY_READ);
        return transactions.inTransaction(TransactionMode.READ_ONLY,
                () -> inventory.find(sku).orElseThrow(() -> new ResourceNotFoundException("unknown sku: " + sku.value())));
    }

    public List<Inventory> list(Actor actor) {
        actor.require(Permission.INVENTORY_READ);
        return transactions.inTransaction(TransactionMode.READ_ONLY, inventory::findAll);
    }
}

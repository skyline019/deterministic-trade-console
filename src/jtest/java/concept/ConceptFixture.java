package concept;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.port.AuditRecorder;
import com.acme.trade.application.port.InventoryOperationRepository;
import com.acme.trade.application.port.InventoryRepository;
import com.acme.trade.application.port.LockHandle;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.application.port.OrderIdGenerator;
import com.acme.trade.application.port.OrderOperationRepository;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.port.OrderRescheduleOperationRepository;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.application.service.InventoryService;
import com.acme.trade.application.service.OrderRescheduleService;
import com.acme.trade.application.service.OrderTransitionService;
import com.acme.trade.application.service.PlaceOrderService;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.model.Inventory;
import com.acme.trade.domain.model.InventoryOperation;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderOperation;
import com.acme.trade.domain.model.OrderRescheduleOperation;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

final class ConceptFixture {
    static final Sku SKU = Sku.of("SKU-1");
    static final Instant NOW = Instant.parse("2026-01-02T03:04:05.123456Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Actor OPERATOR = new Actor("00000000-0000-0000-0000-000000000101", "operator", Set.of(Role.OPERATOR));
    static final Actor VIEWER = new Actor("00000000-0000-0000-0000-000000000102", "viewer", Set.of(Role.VIEWER));

    final FakeInventory inventory = new FakeInventory();
    final FakeOrders orders = new FakeOrders();
    final FakeLocks locks = new FakeLocks();
    final FakeTransactions transactions = new FakeTransactions();
    final FakeAudit audit = new FakeAudit();
    final FakeInventoryOperations inventoryOperations = new FakeInventoryOperations();
    final FakeOrderOperations orderOperations = new FakeOrderOperations();
    final FakeOrderRescheduleOperations rescheduleOperations = new FakeOrderRescheduleOperations();
    final PlaceOrderService service;
    final InventoryService inventoryService;
    final OrderTransitionService transitions;
    final OrderRescheduleService reschedules;

    ConceptFixture() {
        OrderIdGenerator ids = new OrderIdGenerator() {
            private final AtomicLong sequence = new AtomicLong();

            @Override
            public OrderId newId() {
                return new OrderId(new UUID(0, sequence.incrementAndGet()));
            }
        };
        service = new PlaceOrderService(inventory, orders, locks, transactions, ids, CLOCK,
                Duration.ofSeconds(10), Duration.ofHours(1), audit);
        inventoryService = new InventoryService(inventory, inventoryOperations, locks, transactions,
                CLOCK, Duration.ofSeconds(10), audit);
        transitions = new OrderTransitionService(orders, orderOperations, inventory, locks, transactions,
                CLOCK, Duration.ofSeconds(10), audit);
        reschedules = new OrderRescheduleService(orders, rescheduleOperations, locks, transactions, CLOCK,
                Duration.ofSeconds(10), Duration.ofHours(1), audit);
        transactions.rollbackFactory = this::snapshotRollback;
    }

    private Runnable snapshotRollback() {
        var inventorySnapshot = inventory.snapshot();
        var orderSnapshot = orders.snapshot();
        var inventoryOperationSnapshot = inventoryOperations.snapshot();
        var orderOperationSnapshot = orderOperations.snapshot();
        var rescheduleSnapshot = rescheduleOperations.snapshot();
        var auditSnapshot = audit.snapshot();
        return () -> {
            inventory.restore(inventorySnapshot);
            orders.restore(orderSnapshot);
            inventoryOperations.restore(inventoryOperationSnapshot);
            orderOperations.restore(orderOperationSnapshot);
            rescheduleOperations.restore(rescheduleSnapshot);
            audit.restore(auditSnapshot);
        };
    }

    void seed(int quantity) {
        inventory.replaceStock(SKU, new StockLevel(quantity), NOW);
    }

    PlaceOrderCommand command(String requestId, int quantity) {
        return command(OPERATOR, requestId, quantity);
    }

    PlaceOrderCommand command(Actor actor, String requestId, int quantity) {
        return PlaceOrderCommand.of(actor, requestId, SKU.value(), quantity);
    }

    int available() {
        return inventory.find(SKU).orElseThrow().available().value();
    }

    int reserved() {
        return inventory.find(SKU).orElseThrow().reserved().value();
    }

    static final class FakeTransactions implements TransactionRunner {
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        volatile boolean rollbackEnabled = true;
        Supplier<Runnable> rollbackFactory = () -> () -> { };

        @Override
        public <T> T inTransaction(TransactionMode mode, Supplier<T> action) {
            Runnable rollback = null;
            if (mode == TransactionMode.WRITE) {
                writes.incrementAndGet();
                if (rollbackEnabled) {
                    rollback = rollbackFactory.get();
                }
            } else {
                reads.incrementAndGet();
            }
            try {
                return action.get();
            } catch (RuntimeException | Error failure) {
                if (rollback != null) {
                    rollback.run();
                }
                throw failure;
            }
        }

        int writes() {
            return writes.get();
        }

        int reads() {
            return reads.get();
        }
    }

    static final class FakeLocks implements LockManager {
        private final Set<String> held = new HashSet<>();
        boolean available = true;
        int acquired;
        int released;

        @Override
        public synchronized Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (!available || held.contains(key)) {
                return Optional.empty();
            }
            held.add(key);
            acquired++;
            return Optional.of(new LockHandle() {
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public void close() {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    synchronized (FakeLocks.this) {
                        held.remove(key);
                        released++;
                    }
                }
            });
        }
    }

    static final class FakeAudit implements AuditRecorder {
        private final List<String> actions = new ArrayList<>();
        RuntimeException failure;

        @Override
        public synchronized void record(Actor actor, String action, AggregateRef aggregate,
                                        RequestId requestId, Map<String, String> payload) {
            if (failure != null) {
                RuntimeException next = failure;
                failure = null;
                throw next;
            }
            actions.add(action);
        }

        synchronized int size() {
            return actions.size();
        }

        synchronized List<String> snapshot() {
            return List.copyOf(actions);
        }

        synchronized void restore(List<String> snapshot) {
            actions.clear();
            actions.addAll(snapshot);
        }
    }

    static final class FakeInventory implements InventoryRepository {
        private final Map<String, Inventory> values = new HashMap<>();

        @Override
        public synchronized Optional<Inventory> find(Sku sku) {
            return Optional.ofNullable(values.get(sku.value()));
        }

        @Override
        public synchronized List<Inventory> findAll() {
            return values.values().stream().sorted(Comparator.comparing(value -> value.sku().value())).toList();
        }

        @Override
        public synchronized boolean create(Sku sku, StockLevel stock, Instant at) {
            if (values.containsKey(sku.value())) {
                return false;
            }
            values.put(sku.value(), new Inventory(sku, stock, 0, at));
            return true;
        }

        @Override
        public synchronized boolean adjust(Sku sku, int delta, Instant at) {
            Inventory current = values.get(sku.value());
            if (current == null) {
                return false;
            }
            long changed = (long) current.available().value() + delta;
            if (changed < 0 || changed + current.reserved().value() > StockLevel.MAX) {
                return false;
            }
            values.put(sku.value(), new Inventory(sku, new StockLevel((int) changed), current.reserved(),
                    current.version() + 1, at));
            return true;
        }

        @Override
        public synchronized boolean tryReserve(Sku sku, Quantity quantity, Instant at) {
            Inventory current = values.get(sku.value());
            if (current == null || current.available().value() < quantity.value()) {
                return false;
            }
            values.put(sku.value(), current.reserve(quantity, at));
            return true;
        }

        @Override
        public synchronized boolean releaseReservation(Sku sku, Quantity quantity, Instant at) {
            Inventory current = values.get(sku.value());
            if (current == null || current.reserved().value() < quantity.value()) {
                return false;
            }
            values.put(sku.value(), current.release(quantity, at));
            return true;
        }

        @Override
        public synchronized boolean confirmReservation(Sku sku, Quantity quantity, Instant at) {
            Inventory current = values.get(sku.value());
            if (current == null || current.reserved().value() < quantity.value()) {
                return false;
            }
            values.put(sku.value(), current.confirm(quantity, at));
            return true;
        }

        @Override
        public synchronized void replaceStock(Sku sku, StockLevel stock, Instant at) {
            Inventory old = values.get(sku.value());
            values.put(sku.value(), new Inventory(sku, stock, old == null ? 0 : old.version() + 1, at));
        }

        synchronized Map<String, Inventory> snapshot() {
            return Map.copyOf(values);
        }

        synchronized void restore(Map<String, Inventory> snapshot) {
            values.clear();
            values.putAll(snapshot);
        }
    }

    static final class FakeOrders implements OrderRepository {
        private final Map<String, Order> byRequest = new HashMap<>();
        RuntimeException insertFailure;

        @Override
        public synchronized Optional<Order> findByRequestId(RequestId requestId) {
            return Optional.ofNullable(byRequest.get(requestId.value()));
        }

        @Override
        public synchronized Optional<Order> findById(OrderId orderId) {
            return byRequest.values().stream().filter(order -> order.id().equals(orderId)).findFirst();
        }

        @Override
        public synchronized List<Order> findPage(OrderPageQuery query, int fetchLimit) {
            Comparator<Order> order = Comparator.comparing(Order::createdAt)
                    .thenComparing(value -> value.id().toString());
            return byRequest.values().stream()
                    .filter(value -> query.status().map(status -> value.status() == status).orElse(true))
                    .filter(value -> query.sku().map(sku -> value.sku().equals(sku)).orElse(true))
                    .filter(value -> query.cursor().map(cursor -> {
                        int time = value.createdAt().compareTo(cursor.createdAt());
                        return time < 0 || (time == 0 && value.id().toString().compareTo(cursor.id().toString()) < 0);
                    }).orElse(true))
                    .sorted(order.reversed()).limit(fetchLimit).toList();
        }

        @Override
        public synchronized List<Order> findExpiredReserved(Instant now, int limit) {
            return byRequest.values().stream()
                    .filter(order -> order.isExpiredAt(now))
                    .sorted(Comparator.comparing(Order::expiresAt).thenComparing(o -> o.id().toString()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized boolean transitionStatus(OrderId orderId, OrderStatus expected,
                                                      OrderStatus target, Instant at) {
            Order current = findById(orderId).orElse(null);
            if (current == null || current.status() != expected) {
                return false;
            }
            byRequest.put(current.requestId().value(), current.transitionTo(target, at));
            return true;
        }

        @Override
        public synchronized boolean reduceReservedQuantity(OrderId orderId, int expectedQuantity,
                                                           int newQuantity, Instant at) {
            Order current = findById(orderId).orElse(null);
            if (current == null || current.status() != OrderStatus.RESERVED
                    || current.quantity().value() != expectedQuantity) {
                return false;
            }
            byRequest.put(current.requestId().value(),
                    current.confirmUnits(new Quantity(expectedQuantity - newQuantity), at));
            return true;
        }

        @Override
        public synchronized boolean updateExpiresAt(OrderId orderId, Instant expectedExpiresAt,
                                                      Instant newExpiresAt, Instant at) {
            Order current = findById(orderId).orElse(null);
            if (current == null || current.status() != OrderStatus.RESERVED
                    || current.expiresAt() == null || !current.expiresAt().equals(expectedExpiresAt)) {
                return false;
            }
            byRequest.put(current.requestId().value(), current.rescheduleExpiresAt(newExpiresAt, at));
            return true;
        }

        @Override
        public synchronized void insert(Order order) {
            if (insertFailure != null) {
                RuntimeException next = insertFailure;
                insertFailure = null;
                throw next;
            }
            if (byRequest.putIfAbsent(order.requestId().value(), order) != null) {
                throw new DuplicateRequestException(order.requestId().value(), null);
            }
        }

        synchronized int size() {
            return byRequest.size();
        }

        synchronized Map<String, Order> snapshot() {
            return Map.copyOf(byRequest);
        }

        synchronized void restore(Map<String, Order> snapshot) {
            byRequest.clear();
            byRequest.putAll(snapshot);
        }
    }

    static final class FakeInventoryOperations implements InventoryOperationRepository {
        private final Map<String, InventoryOperation> values = new HashMap<>();
        RuntimeException insertFailure;

        @Override
        public synchronized Optional<InventoryOperation> findByRequestId(RequestId requestId) {
            return Optional.ofNullable(values.get(requestId.value()));
        }

        @Override
        public synchronized void insert(InventoryOperation operation) {
            if (insertFailure != null) {
                RuntimeException next = insertFailure;
                insertFailure = null;
                throw next;
            }
            if (values.putIfAbsent(operation.requestId().value(), operation) != null) {
                throw new DuplicateRequestException(operation.requestId().value(), null);
            }
        }

        synchronized Map<String, InventoryOperation> snapshot() {
            return Map.copyOf(values);
        }

        synchronized void restore(Map<String, InventoryOperation> snapshot) {
            values.clear();
            values.putAll(snapshot);
        }

        synchronized int size() {
            return values.size();
        }
    }

    static final class FakeOrderOperations implements OrderOperationRepository {
        private final Map<String, OrderOperation> values = new HashMap<>();
        RuntimeException insertFailure;

        @Override
        public synchronized Optional<OrderOperation> findByRequestId(RequestId requestId) {
            return Optional.ofNullable(values.get(requestId.value()));
        }

        @Override
        public synchronized void insert(OrderOperation operation) {
            if (insertFailure != null) {
                RuntimeException next = insertFailure;
                insertFailure = null;
                throw next;
            }
            if (values.putIfAbsent(operation.requestId().value(), operation) != null) {
                throw new DuplicateRequestException(operation.requestId().value(), null);
            }
        }

        synchronized Map<String, OrderOperation> snapshot() {
            return Map.copyOf(values);
        }

        synchronized void restore(Map<String, OrderOperation> snapshot) {
            values.clear();
            values.putAll(snapshot);
        }

        synchronized int size() {
            return values.size();
        }
    }

    static final class FakeOrderRescheduleOperations implements OrderRescheduleOperationRepository {
        private final Map<String, OrderRescheduleOperation> values = new HashMap<>();

        @Override
        public synchronized Optional<OrderRescheduleOperation> findByRequestId(RequestId requestId) {
            return Optional.ofNullable(values.get(requestId.value()));
        }

        @Override
        public synchronized void insert(OrderRescheduleOperation operation) {
            if (values.putIfAbsent(operation.requestId().value(), operation) != null) {
                throw new DuplicateRequestException(operation.requestId().value(), null);
            }
        }

        synchronized Map<String, OrderRescheduleOperation> snapshot() {
            return Map.copyOf(values);
        }

        synchronized void restore(Map<String, OrderRescheduleOperation> snapshot) {
            values.clear();
            values.putAll(snapshot);
        }

        synchronized int size() {
            return values.size();
        }
    }
}

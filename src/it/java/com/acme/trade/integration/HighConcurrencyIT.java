package com.acme.trade.integration;

import com.acme.trade.adapter.persistence.MySqlAuditRepository;
import com.acme.trade.adapter.persistence.MySqlMaintenanceStore;
import com.acme.trade.adapter.persistence.MySqlInventoryRepository;
import com.acme.trade.adapter.persistence.MySqlOrderRepository;
import com.acme.trade.adapter.redis.RedisLockManager;
import com.acme.trade.adapter.transaction.SpringTransactionRunner;
import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.InsufficientStockException;
import com.acme.trade.application.port.InventoryRepository;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.result.PlaceOrderResult;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.application.service.AuditService;
import com.acme.trade.application.service.PlaceOrderService;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.audit.AuditChainVerifier;
import com.acme.trade.domain.audit.AuditEvent;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Run explicitly with: ./mvnw -Pintegration verify (Docker required). */
class HighConcurrencyIT {
    private static final Sku SKU = Sku.of("SKU-IT");
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Actor ACTOR = new Actor("00000000-0000-0000-0000-000000000001",
            "integration", Set.of(Role.ADMIN));

    private static MySQLContainer<?> mysql;
    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory redisConnections;
    private static StringRedisTemplate redisTemplate;
    private static JdbcTemplate jdbc;
    private static InventoryRepository inventory;
    private static OrderRepository orders;
    private static TransactionRunner transactions;
    private static DataSourceTransactionManager transactionManager;
    private static RedisLockManager lockManager;
    private static PlaceOrderService service;
    private static AuditService audit;
    private static MySqlAuditRepository auditRepository;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("trade").withUsername("trade").withPassword("trade");
        redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
        mysql.start();
        redis.start();

        String url = mysql.getJdbcUrl()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // Apply the real migrations (not a mirrored schema.sql) so V2..Vn are exercised by this IT.
        Flyway.configure()
                .dataSource(url, mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
                .migrate();

        redisConnections = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        redisConnections.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(redisConnections);
        inventory = new MySqlInventoryRepository(jdbc);
        orders = new MySqlOrderRepository(jdbc);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new SpringTransactionRunner(transactionManager, 5);
        lockManager = new RedisLockManager(redisTemplate,
                new ClassPathResource("redis/acquire-lock.lua"),
                new ClassPathResource("redis/release-lock.lua"));
        auditRepository = new MySqlAuditRepository(jdbc);
        audit = new AuditService(auditRepository, UUID::randomUUID, Clock.systemUTC(), transactions);
        service = new PlaceOrderService(inventory, orders, lockManager, transactions,
                OrderId::random, Clock.systemUTC(), LOCK_TTL, java.time.Duration.ofHours(1), audit);
    }

    @AfterAll
    static void stopInfrastructure() {
        if (redisConnections != null) {
            redisConnections.destroy();
        }
        if (redis != null) {
            redis.close();
        }
        if (mysql != null) {
            mysql.close();
        }
    }

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM audit_events_archive");
        jdbc.update("DELETE FROM order_reschedule_operations");
        jdbc.update("DELETE FROM order_operations");
        jdbc.update("DELETE FROM inventory_operations");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM inventory");
        try (var connection = redisConnections.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void contended_orders_never_oversell() throws Exception {
        inventory.replaceStock(SKU, new StockLevel(75), Instant.now());
        int requestCount = 120;
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                int request = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.place(PlaceOrderCommand.of(ACTOR, "contend-" + request, SKU.value(), 1));
                        return "RESERVED";
                    } catch (InsufficientStockException exception) {
                        return "OUT_OF_STOCK";
                    }
                }));
            }
            start.countDown();

            int reserved = 0;
            int outOfStock = 0;
            for (Future<String> future : futures) {
                String outcome = future.get(45, TimeUnit.SECONDS);
                if (outcome.equals("RESERVED")) {
                    reserved++;
                } else {
                    outOfStock++;
                }
            }
            assertThat(reserved).isEqualTo(75);
            assertThat(outOfStock).isEqualTo(45);
            assertThat(inventory.find(SKU)).get().extracting(value -> value.available().value()).isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(75);
            assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM orders", Integer.class)).isEqualTo(75);
            assertThat(inventory.find(SKU).orElseThrow().reserved().value()).isEqualTo(75);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isEqualTo(150);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void same_key_concurrency_commits_one_order() throws Exception {
        inventory.replaceStock(SKU, new StockLevel(10), Instant.now());
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<PlaceOrderResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                tasks.add(() -> {
                    start.await();
                    return service.place(PlaceOrderCommand.of(ACTOR, "same-request", SKU.value(), 2));
                });
            }
            List<Future<PlaceOrderResult>> futures = new ArrayList<>();
            tasks.forEach(task -> futures.add(pool.submit(task)));
            start.countDown();

            List<PlaceOrderResult> results = new ArrayList<>();
            for (Future<PlaceOrderResult> future : futures) {
                // A caller can receive REQUEST_IN_PROGRESS; retrying after the winner finishes is the contract.
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertThat(exception.getCause())
                            .isInstanceOf(com.acme.trade.application.exception.RequestInProgressException.class);
                }
            }
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            String id = results.stream().filter(result -> !result.replayed()).findFirst().orElseThrow().order().id().toString();
            results.forEach(result -> assertThat(result.order().id().toString()).isEqualTo(id));

            PlaceOrderResult retry = service.place(PlaceOrderCommand.of(ACTOR, "same-request", SKU.value(), 2));
            assertThat(retry.replayed()).isTrue();
            assertThat(retry.order().id().toString()).isEqualTo(id);
            assertThat(inventory.find(SKU).orElseThrow().available().value()).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unique_key_race_rolls_back_losing_stock_update_without_redis_lock() throws Exception {
        inventory.replaceStock(SKU, new StockLevel(10), Instant.now());
        CyclicBarrier bothReadBeforeEitherWrites = new CyclicBarrier(2);
        OrderRepository racingOrders = new BarrierOrderRepository(orders, bothReadBeforeEitherWrites);
        LockManager noLock = (key, ttl) -> Optional.of(() -> { });
        PlaceOrderService racingService = new PlaceOrderService(inventory, racingOrders, noLock, transactions,
                OrderId::random, Clock.systemUTC(), LOCK_TTL, java.time.Duration.ofHours(1), audit);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<PlaceOrderResult> call = () -> {
                start.await();
                return racingService.place(PlaceOrderCommand.of(ACTOR, "database-race", SKU.value(), 3));
            };
            Future<PlaceOrderResult> first = pool.submit(call);
            Future<PlaceOrderResult> second = pool.submit(call);
            start.countDown();

            PlaceOrderResult one = first.get(30, TimeUnit.SECONDS);
            PlaceOrderResult two = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(one.replayed(), two.replayed())).containsExactlyInAnyOrder(true, false);
            assertThat(one.order().id()).isEqualTo(two.order().id());
            assertThat(inventory.find(SKU).orElseThrow().available().value()).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stale_handle_cannot_release_a_new_owners_lock() {
        String key = "trade:test:ownership";
        var handle = lockManager.tryAcquire(key, LOCK_TTL).orElseThrow();
        assertThat(lockManager.tryAcquire(key, LOCK_TTL)).isEmpty();

        redisTemplate.opsForValue().set(key, "a-different-owner");
        handle.close();

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("a-different-owner");
        redisTemplate.delete(key);
    }

    /**
     * Archive must relocate a chain, not truncate its history: reads span both tables, the sequence
     * continues after an archive-cut tip, and verification still covers the archived prefix.
     */
    @Test
    void archiving_a_chain_preserves_sequence_and_verifiability() {
        AggregateRef aggregate = AggregateRef.of(AggregateType.ORDER, UUID.randomUUID().toString());
        audit.record(ACTOR, "ORDER_PLACED", aggregate, new RequestId("archive-seed-1"), Map.of("step", "1"));
        audit.record(ACTOR, "ORDER_CONFIRMED", aggregate, new RequestId("archive-seed-2"), Map.of("step", "2"));

        MySqlMaintenanceStore maintenance = new MySqlMaintenanceStore(jdbc, new TransactionTemplate(transactionManager));
        int archived = maintenance.archiveQuietAggregates(
                Instant.now().plusSeconds(3600), Instant.now(), 10);
        assertThat(archived).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Long.class)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events_archive", Long.class)).isEqualTo(2L);

        List<AuditEvent> relocated = auditRepository.findChain(aggregate);
        assertThat(relocated).hasSize(2);
        assertThat(auditRepository.findLast(aggregate).orElseThrow().sequence()).isEqualTo(2L);
        assertThat(AuditChainVerifier.verify(relocated).valid()).isTrue();

        audit.record(ACTOR, "ORDER_CANCELLED", aggregate, new RequestId("archive-seed-3"), Map.of("step", "3"));
        List<AuditEvent> grown = auditRepository.findChain(aggregate);
        assertThat(grown).hasSize(3);
        assertThat(grown.get(2).sequence()).isEqualTo(3L);
        assertThat(grown.get(2).previousHash()).isEqualTo(grown.get(1).eventHash());

        var verification = audit.verify(ACTOR, aggregate);
        assertThat(verification.valid()).isTrue();
        assertThat(verification.checkedEvents()).isEqualTo(3);
    }

    private record BarrierOrderRepository(OrderRepository delegate, CyclicBarrier barrier) implements OrderRepository {
        @Override
        public Optional<Order> findByRequestId(RequestId requestId) {
            Optional<Order> result = delegate.findByRequestId(requestId);
            if (result.isEmpty()) {
                try {
                    barrier.await(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("race barrier failed", exception);
                }
            }
            return result;
        }

        @Override
        public Optional<Order> findById(OrderId orderId) {
            return delegate.findById(orderId);
        }

        @Override
        public List<Order> findPage(OrderPageQuery query, int fetchLimit) {
            return delegate.findPage(query, fetchLimit);
        }

        @Override
        public List<Order> findExpiredReserved(Instant now, int limit) {
            return delegate.findExpiredReserved(now, limit);
        }

        @Override
        public boolean transitionStatus(OrderId orderId, OrderStatus expected, OrderStatus target, Instant at) {
            return delegate.transitionStatus(orderId, expected, target, at);
        }

        @Override
        public boolean reduceReservedQuantity(OrderId orderId, int expectedQuantity, int newQuantity, Instant at) {
            return delegate.reduceReservedQuantity(orderId, expectedQuantity, newQuantity, at);
        }

        @Override
        public boolean updateExpiresAt(OrderId orderId, Instant expectedExpiresAt, Instant newExpiresAt, Instant at) {
            return delegate.updateExpiresAt(orderId, expectedExpiresAt, newExpiresAt, at);
        }

        @Override
        public void insert(Order order) {
            delegate.insert(order);
        }
    }
}

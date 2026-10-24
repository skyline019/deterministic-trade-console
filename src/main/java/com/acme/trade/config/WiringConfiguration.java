package com.acme.trade.config;

import com.acme.trade.adapter.id.UuidOrderIdGenerator;
import com.acme.trade.adapter.id.UuidValueGenerator;
import com.acme.trade.adapter.observability.MeteredPlaceOrderUseCase;
import com.acme.trade.adapter.persistence.MySqlAuditRepository;
import com.acme.trade.adapter.persistence.MySqlDashboardRepository;
import com.acme.trade.adapter.persistence.MySqlInventoryOperationRepository;
import com.acme.trade.adapter.persistence.MySqlInventoryRepository;
import com.acme.trade.adapter.persistence.MySqlOrderOperationRepository;
import com.acme.trade.adapter.persistence.MySqlOrderRepository;
import com.acme.trade.adapter.persistence.MySqlOrderRescheduleOperationRepository;
import com.acme.trade.adapter.redis.RedisLockManager;
import com.acme.trade.adapter.redis.RetryingLockManager;
import com.acme.trade.adapter.transaction.SpringTransactionRunner;
import com.acme.trade.adapter.web.ApiRoutes;
import com.acme.trade.adapter.web.AuditHandler;
import com.acme.trade.adapter.web.AuthHandler;
import com.acme.trade.adapter.web.DashboardHandler;
import com.acme.trade.adapter.web.InventoryHandler;
import com.acme.trade.adapter.web.OrderHandler;
import com.acme.trade.application.port.AuditRecorder;
import com.acme.trade.application.port.AuditRepository;
import com.acme.trade.application.port.DashboardRepository;
import com.acme.trade.application.port.InventoryOperationRepository;
import com.acme.trade.application.port.InventoryRepository;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.application.port.OrderIdGenerator;
import com.acme.trade.application.port.OrderOperationRepository;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.port.OrderRescheduleOperationRepository;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.service.AuditService;
import com.acme.trade.application.service.AuthService;
import com.acme.trade.application.service.DashboardService;
import com.acme.trade.application.service.InventoryService;
import com.acme.trade.application.service.OrderQueryService;
import com.acme.trade.application.service.OrderRescheduleService;
import com.acme.trade.application.service.OrderTransitionService;
import com.acme.trade.application.service.PlaceOrderService;
import com.acme.trade.application.usecase.PlaceOrderUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/** Business composition root. Security has its own concept-specific composition root. */
@Configuration(proxyBeanMethods = false)
public class WiringConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TradeSettings tradeSettings(Environment environment) {
        Binder binder = Binder.get(environment);
        return new TradeSettings(
                binder.bind("trade.lock-ttl", Duration.class).orElse(Duration.ofSeconds(10)),
                binder.bind("trade.transaction-timeout-seconds", Integer.class).orElse(5),
                binder.bind("trade.lock-retry-attempts", Integer.class).orElse(3),
                binder.bind("trade.lock-retry-backoff", Duration.class).orElse(Duration.ofMillis(50)),
                binder.bind("trade.reservation-ttl", Duration.class).orElse(Duration.ofHours(1)));
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    InventoryRepository inventoryRepository(JdbcTemplate jdbc) {
        return new MySqlInventoryRepository(jdbc);
    }

    @Bean
    OrderRepository orderRepository(JdbcTemplate jdbc) {
        return new MySqlOrderRepository(jdbc);
    }

    @Bean
    InventoryOperationRepository inventoryOperationRepository(JdbcTemplate jdbc) {
        return new MySqlInventoryOperationRepository(jdbc);
    }

    @Bean
    OrderOperationRepository orderOperationRepository(JdbcTemplate jdbc) {
        return new MySqlOrderOperationRepository(jdbc);
    }

    @Bean
    OrderRescheduleOperationRepository orderRescheduleOperationRepository(JdbcTemplate jdbc) {
        return new MySqlOrderRescheduleOperationRepository(jdbc);
    }

    @Bean
    AuditRepository auditRepository(JdbcTemplate jdbc) {
        return new MySqlAuditRepository(jdbc);
    }

    @Bean
    DashboardRepository dashboardRepository(JdbcTemplate jdbc) {
        return new MySqlDashboardRepository(jdbc);
    }

    @Bean
    TransactionRunner transactionRunner(DataSourceTransactionManager manager, TradeSettings settings) {
        return new SpringTransactionRunner(manager, settings.transactionTimeoutSeconds());
    }

    @Bean
    LockManager lockManager(StringRedisTemplate redis, TradeSettings settings) {
        LockManager redisLocks = new RedisLockManager(redis, new ClassPathResource("redis/acquire-lock.lua"),
                new ClassPathResource("redis/release-lock.lua"));
        return new RetryingLockManager(redisLocks, settings.lockRetryAttempts(), settings.lockRetryBackoff());
    }

    @Bean
    OrderIdGenerator orderIdGenerator() {
        return new UuidOrderIdGenerator();
    }

    @Bean
    UuidValueGenerator uuidValueGenerator() {
        return new UuidValueGenerator();
    }

    @Bean
    AuditService auditService(AuditRepository audits, UuidValueGenerator ids,
                              Clock clock, TransactionRunner transactions) {
        return new AuditService(audits, ids, clock, transactions);
    }

    @Bean
    PlaceOrderUseCase placeOrderUseCase(InventoryRepository inventory, OrderRepository orders,
                                        LockManager locks, TransactionRunner transactions,
                                        OrderIdGenerator orderIds, Clock clock, TradeSettings settings,
                                        AuditRecorder audit, MeterRegistry metrics) {
        PlaceOrderService core = new PlaceOrderService(inventory, orders, locks, transactions,
                orderIds, clock, settings.lockTtl(), settings.reservationTtl(), audit);
        return new MeteredPlaceOrderUseCase(core, metrics);
    }

    @Bean
    InventoryService inventoryService(InventoryRepository inventory, InventoryOperationRepository operations,
                                      LockManager locks, TransactionRunner transactions, Clock clock,
                                      TradeSettings settings, AuditRecorder audit) {
        return new InventoryService(inventory, operations, locks, transactions, clock, settings.lockTtl(), audit);
    }

    @Bean
    OrderQueryService orderQueryService(OrderRepository orders, TransactionRunner transactions) {
        return new OrderQueryService(orders, transactions);
    }

    @Bean
    OrderTransitionService orderTransitionService(OrderRepository orders, OrderOperationRepository operations,
                                                  InventoryRepository inventory, LockManager locks,
                                                  TransactionRunner transactions, Clock clock,
                                                  TradeSettings settings, AuditRecorder audit) {
        return new OrderTransitionService(orders, operations, inventory, locks, transactions,
                clock, settings.lockTtl(), audit);
    }

    @Bean
    OrderRescheduleService orderRescheduleService(OrderRepository orders,
                                                  OrderRescheduleOperationRepository operations,
                                                  LockManager locks, TransactionRunner transactions,
                                                  Clock clock, TradeSettings settings, AuditRecorder audit) {
        return new OrderRescheduleService(orders, operations, locks, transactions, clock,
                settings.lockTtl(), settings.reservationTtl(), audit);
    }

    @Bean
    DashboardService dashboardService(DashboardRepository dashboard, TransactionRunner transactions) {
        return new DashboardService(dashboard, transactions);
    }

    @Bean
    AuthHandler authHandler(AuthService auth, SecuritySettings securitySettings) {
        return new AuthHandler(auth, securitySettings.cookieSecure(), securitySettings.passwordLoginEnabled());
    }

    @Bean
    OrderHandler orderHandler(PlaceOrderUseCase placeOrder, OrderQueryService queries,
                              OrderTransitionService transitions, OrderRescheduleService reschedules) {
        return new OrderHandler(placeOrder, queries, transitions, reschedules);
    }

    @Bean
    InventoryHandler inventoryHandler(InventoryService inventory) {
        return new InventoryHandler(inventory);
    }

    @Bean
    AuditHandler auditHandler(AuditService audits) {
        return new AuditHandler(audits);
    }

    @Bean
    DashboardHandler dashboardHandler(DashboardService dashboard) {
        return new DashboardHandler(dashboard);
    }

    @Bean
    RouterFunction<ServerResponse> apiRoutes(AuthHandler auth, OrderHandler orders, InventoryHandler inventory,
                                             AuditHandler audits, DashboardHandler dashboard) {
        return ApiRoutes.routes(auth, orders, inventory, audits, dashboard);
    }
}

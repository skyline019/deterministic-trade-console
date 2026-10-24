package com.acme.trade.integration;

import com.acme.trade.application.port.PasswordCodec;
import com.acme.trade.application.port.UserRepository;
import com.acme.trade.domain.security.Role;
import com.acme.trade.domain.security.UserAccount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP + MySQL + Redis E2E. Executed only by the integration profile with Docker available. */
@Testcontainers
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "trade.security.bootstrap-admin-password=integration-secret",
        "trade.security.bcrypt-strength=10",
        "trade.security.login-attempt-limit=3",
        "trade.security.login-window=5m",
        "spring.datasource.hikari.maximum-pool-size=24",
        "logging.level.com.acme.trade=WARN"
})
class HttpApiE2eIT {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("trade").withUsername("trade").withPassword("trade");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true");
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordCodec passwords;

    @BeforeEach
    void cleanBusinessState() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM order_operations");
        jdbc.update("DELETE FROM inventory_operations");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM inventory");
        jdbc.update("DELETE FROM api_tokens");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void health_is_public_but_api_and_metrics_require_explicit_bearer_identity() throws Exception {
        HttpResult health = call("GET", "/actuator/health", null, null, null);
        assertThat(health.status).isEqualTo(200);
        assertThat(health.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(health.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(health.header("Content-Security-Policy")).contains("default-src 'self'");
        assertThat(health.header("Content-Security-Policy")).contains("object-src 'none'");
        assertThat(health.header("X-Request-Id")).isNotBlank();

        HttpResult missing = call("GET", "/api/v1/dashboard", null, null, null);
        HttpResult malformed = call("GET", "/api/v1/dashboard", "not-a-token", null, null);
        assertThat(missing.status).isEqualTo(401);
        assertThat(malformed.status).isEqualTo(401);
        assertThat(missing.json().path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(missing.body).doesNotContain("Bearer", "password", "stacktrace");
        assertThat(missing.header("Cache-Control")).isEqualTo("no-store");

        String token = login("admin", "integration-secret");
        assertThat(call("GET", "/actuator/prometheus", token, null, null).status).isEqualTo(200);
    }

    @Test
    void login_persists_only_hash_and_logout_revokes_immediately() throws Exception {
        HttpResult unknown = call("POST", "/api/v1/auth/token", null, null,
                Map.of("username", "missing-user", "password", "wrong-password"));
        HttpResult wrong = call("POST", "/api/v1/auth/token", null, null,
                Map.of("username", "admin", "password", "wrong-password"));
        assertThat(unknown.status).isEqualTo(401);
        assertThat(wrong.status).isEqualTo(401);
        assertThat(unknown.json().path("code").asText()).isEqualTo(wrong.json().path("code").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_tokens", Integer.class)).isZero();

        String token = login("admin", "integration-secret");
        assertThat(token).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_tokens", Integer.class)).isEqualTo(1);
        String persistedHash = jdbc.queryForObject("SELECT token_hash FROM api_tokens", String.class);
        assertThat(persistedHash).hasSize(64).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_tokens WHERE token_hash = ?", Integer.class, token))
                .isZero();

        HttpResult me = call("GET", "/api/v1/auth/me", token, null, null);
        assertThat(me.status).isEqualTo(200);
        assertThat(me.json().path("username").asText()).isEqualTo("admin");
        assertThat(me.json().path("roles").toString()).contains("ADMIN");
        assertThat(call("DELETE", "/api/v1/auth/token", token, null, null).status).isEqualTo(204);
        assertThat(call("GET", "/api/v1/auth/me", token, null, null).status).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_tokens", Integer.class)).isZero();
    }

    @Test
    void complete_command_query_audit_and_dashboard_flow_is_replay_safe() throws Exception {
        String token = login("admin", "integration-secret");
        HttpResult created = call("POST", "/api/v1/inventory", token, "e2e-create",
                Map.of("sku", "SKU-E2E", "initialStock", 5, "reason", "initial e2e stock"));
        assertThat(created.status).isEqualTo(201);
        assertThat(created.json().path("resultingAvailable").asInt()).isEqualTo(5);
        HttpResult createReplay = call("POST", "/api/v1/inventory", token, "e2e-create",
                Map.of("sku", "SKU-E2E", "initialStock", 5, "reason", "initial e2e stock"));
        assertThat(createReplay.status).isEqualTo(200);
        assertThat(createReplay.header("Idempotent-Replay")).isEqualTo("true");
        assertThat(createReplay.json().path("requestId")).isEqualTo(created.json().path("requestId"));

        HttpResult placed = call("POST", "/api/v1/orders", token, "e2e-place",
                Map.of("sku", "SKU-E2E", "quantity", 2));
        assertThat(placed.status).isEqualTo(201);
        String orderId = placed.json().path("id").asText();
        assertThat(placed.json().path("status").asText()).isEqualTo("RESERVED");
        HttpResult replay = call("POST", "/api/v1/orders", token, "e2e-place",
                Map.of("sku", "SKU-E2E", "quantity", 2));
        assertThat(replay.status).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.json().path("id").asText()).isEqualTo(orderId);
        HttpResult conflict = call("POST", "/api/v1/orders", token, "e2e-place",
                Map.of("sku", "SKU-E2E", "quantity", 1));
        assertThat(conflict.status).isEqualTo(409);
        assertThat(conflict.json().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);

        HttpResult inventory = call("GET", "/api/v1/inventory/SKU-E2E", token, null, null);
        assertThat(inventory.json().path("available").asInt()).isEqualTo(3);
        assertThat(inventory.json().path("reserved").asInt()).isEqualTo(2);
        HttpResult confirmed = call("POST", "/api/v1/orders/" + orderId + "/confirmation", token,
                "e2e-confirm", Map.of("reason", "payment captured"));
        assertThat(confirmed.status).isEqualTo(200);
        assertThat(confirmed.json().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.json().path("version").asLong()).isEqualTo(1);
        assertThat(call("POST", "/api/v1/orders/" + orderId + "/confirmation", token,
                "e2e-confirm", Map.of("reason", "payment captured")).json().path("version").asLong()).isEqualTo(1);
        assertThat(call("POST", "/api/v1/orders/" + orderId + "/cancellation", token,
                "e2e-illegal-cancel", Map.of("reason", "too late")).status).isEqualTo(409);

        HttpResult finalInventory = call("GET", "/api/v1/inventory/SKU-E2E", token, null, null);
        assertThat(finalInventory.json().path("available").asInt()).isEqualTo(3);
        assertThat(finalInventory.json().path("reserved").asInt()).isZero();
        HttpResult events = call("GET", "/api/v1/audit/events?type=ORDER&id=" + orderId, token, null, null);
        assertThat(events.status).isEqualTo(200);
        assertThat(events.json()).hasSize(2);
        assertThat(events.json().get(0).path("action").asText()).isEqualTo("ORDER_RESERVED");
        assertThat(events.json().get(1).path("action").asText()).isEqualTo("ORDER_CONFIRMED");
        HttpResult verification = call("GET", "/api/v1/audit/verification?type=ORDER&id=" + orderId,
                token, null, null);
        assertThat(verification.json().path("valid").asBoolean()).isTrue();
        assertThat(verification.json().path("checkedEvents").asInt()).isEqualTo(2);

        jdbc.update("UPDATE audit_events SET payload_json = ? WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                "{\"tampered\":true}", orderId);
        HttpResult tampered = call("GET", "/api/v1/audit/verification?type=ORDER&id=" + orderId,
                token, null, null);
        assertThat(tampered.json().path("valid").asBoolean()).isFalse();

        HttpResult dashboard = call("GET", "/api/v1/dashboard", token, null, null);
        assertThat(dashboard.json().path("skuCount").asInt()).isEqualTo(1);
        assertThat(dashboard.json().path("confirmedOrders").asInt()).isEqualTo(1);
        assertThat(dashboard.json().path("totalOrders").asInt()).isEqualTo(1);
    }

    @Test
    void cancellation_releases_stock_and_keyset_pages_have_no_gaps_or_duplicates() throws Exception {
        String token = login("admin", "integration-secret");
        assertThat(call("POST", "/api/v1/inventory", token, "page-create",
                Map.of("sku", "SKU-PAGE", "initialStock", 5, "reason", "pagination seed")).status).isEqualTo(201);
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < 3; index++) {
            HttpResult response = call("POST", "/api/v1/orders", token, "page-place-" + index,
                    Map.of("sku", "SKU-PAGE", "quantity", 1));
            assertThat(response.status).isEqualTo(201);
            ids.add(response.json().path("id").asText());
        }
        String cancelled = ids.iterator().next();
        assertThat(call("POST", "/api/v1/orders/" + cancelled + "/cancellation", token, "page-cancel",
                Map.of("reason", "buyer cancelled")).status).isEqualTo(200);
        HttpResult stock = call("GET", "/api/v1/inventory/SKU-PAGE", token, null, null);
        assertThat(stock.json().path("available").asInt()).isEqualTo(3);
        assertThat(stock.json().path("reserved").asInt()).isEqualTo(2);

        HttpResult first = call("GET", "/api/v1/orders?limit=2&sku=SKU-PAGE", token, null, null);
        assertThat(first.json().path("items")).hasSize(2);
        String cursor = first.json().path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        HttpResult second = call("GET", "/api/v1/orders?limit=2&sku=SKU-PAGE&cursor=" + cursor,
                token, null, null);
        assertThat(second.json().path("items")).hasSize(1);
        assertThat(second.json().path("nextCursor").isMissingNode()
                || second.json().path("nextCursor").isNull()).isTrue();
        Set<String> paged = new HashSet<>();
        first.json().path("items").forEach(item -> paged.add(item.path("id").asText()));
        second.json().path("items").forEach(item -> paged.add(item.path("id").asText()));
        assertThat(paged).isEqualTo(ids);
        HttpResult cancelledOnly = call("GET", "/api/v1/orders?status=CANCELLED&sku=SKU-PAGE&limit=100",
                token, null, null);
        assertThat(cancelledOnly.json().path("items")).hasSize(1);
        assertThat(cancelledOnly.json().path("items").get(0).path("id").asText()).isEqualTo(cancelled);
    }

    @Test
    void rbac_and_actuator_permissions_match_the_application_capability_matrix() throws Exception {
        String admin = login("admin", "integration-secret");
        assertThat(call("POST", "/api/v1/inventory", admin, "rbac-seed",
                Map.of("sku", "SKU-RBAC", "initialStock", 100, "reason", "rbac seed")).status).isEqualTo(201);

        for (Role role : Role.values()) {
            String token = role == Role.ADMIN ? admin : createUserAndLogin(role);
            assertThat(call("GET", "/api/v1/inventory", token, null, null).status).isEqualTo(200);
            assertThat(call("GET", "/api/v1/orders", token, null, null).status).isEqualTo(200);
            assertThat(call("GET", "/api/v1/dashboard", token, null, null).status).isEqualTo(200);
            boolean writer = role == Role.ADMIN || role == Role.OPERATOR;
            HttpResult create = call("POST", "/api/v1/inventory", token, "rbac-create-" + role,
                    Map.of("sku", "SKU-" + role, "initialStock", 1, "reason", "role probe"));
            assertThat(create.status).isEqualTo(writer ? 201 : 403);
            HttpResult place = call("POST", "/api/v1/orders", token, "rbac-place-" + role,
                    Map.of("sku", "SKU-RBAC", "quantity", 1));
            assertThat(place.status).isEqualTo(writer ? 201 : 403);
            boolean auditor = role == Role.ADMIN || role == Role.AUDITOR;
            assertThat(call("GET", "/api/v1/audit/events?type=INVENTORY&id=SKU-RBAC",
                    token, null, null).status).isEqualTo(auditor ? 200 : 403);
            assertThat(call("GET", "/actuator/prometheus", token, null, null).status)
                    .isEqualTo(auditor ? 200 : 403);
        }
        HttpResult metrics = call("GET", "/actuator/prometheus", admin, null, null);
        assertThat(metrics.body).contains("trade_order_requests_total").doesNotContain("rbac-place-");
    }

    @Test
    void invalid_bodies_keys_limits_states_and_missing_resources_have_stable_failures() throws Exception {
        String token = login("admin", "integration-secret");
        assertThat(call("POST", "/api/v1/inventory", token, "failure-seed",
                Map.of("sku", "SKU-FAIL", "initialStock", 2, "reason", "failure seed")).status).isEqualTo(201);
        assertError(call("POST", "/api/v1/inventory", token, null,
                Map.of("sku", "SKU-NO-KEY", "initialStock", 1, "reason", "missing key")), 400, "INVALID_REQUEST");
        assertError(rawCall("POST", "/api/v1/inventory", token, "bad-json", "{not-json"),
                400, "INVALID_REQUEST");
        assertError(call("POST", "/api/v1/inventory", token, "bad-stock",
                Map.of("sku", "SKU-NEGATIVE", "initialStock", -1, "reason", "negative")), 400, "INVALID_REQUEST");
        assertError(call("POST", "/api/v1/inventory", token, "duplicate-sku",
                Map.of("sku", "SKU-FAIL", "initialStock", 2, "reason", "duplicate")), 409, "RESOURCE_CONFLICT");
        assertError(call("POST", "/api/v1/inventory/SKU-MISSING/adjustments", token, "missing-adjust",
                Map.of("delta", 1, "reason", "missing")), 409, "STOCK_ADJUSTMENT_REJECTED");
        assertError(call("POST", "/api/v1/orders", token, "zero-quantity",
                Map.of("sku", "SKU-FAIL", "quantity", 0)), 400, "INVALID_REQUEST");
        assertError(call("POST", "/api/v1/orders", token, "large-quantity",
                Map.of("sku", "SKU-FAIL", "quantity", 10001)), 400, "INVALID_REQUEST");
        assertError(call("POST", "/api/v1/orders", token, "missing-sku",
                Map.of("sku", "SKU-UNKNOWN", "quantity", 1)), 409, "OUT_OF_STOCK");
        assertError(call("POST", "/api/v1/orders", token, "out-of-stock",
                Map.of("sku", "SKU-FAIL", "quantity", 3)), 409, "OUT_OF_STOCK");
        assertError(call("POST", "/api/v1/orders/not-a-uuid/cancellation", token, "bad-order-id",
                Map.of("reason", "bad id")), 400, "INVALID_REQUEST");
        assertError(call("POST", "/api/v1/orders/" + UUID.randomUUID() + "/cancellation", token, "unknown-order",
                Map.of("reason", "unknown")), 404, "NOT_FOUND");
        assertError(call("POST", "/api/v1/orders/" + UUID.randomUUID() + "/cancellation", token, "blank-reason",
                Map.of("reason", " ")), 400, "INVALID_REQUEST");
        assertError(call("POST", "/api/v1/orders/" + UUID.randomUUID() + "/cancellation", token, "long-reason",
                Map.of("reason", "x".repeat(201))), 400, "INVALID_REQUEST");
        for (String path : List.of("/api/v1/orders?limit=0", "/api/v1/orders?limit=101",
                "/api/v1/orders?cursor=not-a-cursor", "/api/v1/orders?status=UNKNOWN",
                "/api/v1/audit/events?type=ORDER&id=x&limit=0",
                "/api/v1/audit/events?type=ORDER&id=x&limit=501")) {
            assertError(call("GET", path, token, null, null), 400, "INVALID_REQUEST");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM inventory WHERE sku='SKU-FAIL'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void real_lua_login_throttle_limits_and_successful_login_resets_only_its_bucket() throws Exception {
        String username = "throttle-" + UUID.randomUUID().toString().substring(0, 8);
        users.insert(new UserAccount(UUID.randomUUID(), username, passwords.hash("correct-password"),
                true, Set.of(Role.VIEWER)));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(call("POST", "/api/v1/auth/token", null, null,
                    Map.of("username", username, "password", "wrong-password")).status).isEqualTo(401);
        }
        HttpResult limited = call("POST", "/api/v1/auth/token", null, null,
                Map.of("username", username, "password", "correct-password"));
        assertThat(limited.status).isEqualTo(429);
        assertThat(limited.json().path("code").asText()).isEqualTo("LOGIN_RATE_LIMITED");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 300L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_tokens", Integer.class)).isZero();

        String other = "throttle-" + UUID.randomUUID().toString().substring(0, 8);
        users.insert(new UserAccount(UUID.randomUUID(), other, passwords.hash("correct-password"),
                true, Set.of(Role.VIEWER)));
        login(other, "correct-password");
        jdbc.update("DELETE FROM api_tokens");
        for (int attempt = 0; attempt < 2; attempt++) {
            call("POST", "/api/v1/auth/token", null, null,
                    Map.of("username", other, "password", "wrong-password"));
        }
        String token = login(other, "correct-password");
        assertThat(token).isNotBlank();
    }

    @Test
    void concurrent_http_commands_never_oversell_and_keep_both_ledgers_consistent() throws Exception {
        String token = login("admin", "integration-secret");
        assertThat(call("POST", "/api/v1/inventory", token, "http-pressure-seed",
                Map.of("sku", "SKU-HTTP-PRESSURE", "initialStock", 10, "reason", "http pressure")).status)
                .isEqualTo(201);
        int requests = 40;
        var pool = Executors.newFixedThreadPool(12);
        try {
            List<java.util.concurrent.Future<HttpResult>> futures = new ArrayList<>();
            for (int index = 0; index < requests; index++) {
                int request = index;
                futures.add(pool.submit(() -> call("POST", "/api/v1/orders", token, "http-pressure-" + request,
                        Map.of("sku", "SKU-HTTP-PRESSURE", "quantity", 1))));
            }
            int created = 0;
            int outOfStock = 0;
            for (var future : futures) {
                HttpResult result = future.get(45, TimeUnit.SECONDS);
                if (result.status == 201) {
                    created++;
                } else {
                    assertThat(result.status).isEqualTo(409);
                    assertThat(result.json().path("code").asText()).isEqualTo("OUT_OF_STOCK");
                    outOfStock++;
                }
            }
            assertThat(created).isEqualTo(10);
            assertThat(outOfStock).isEqualTo(30);
        } finally {
            pool.shutdownNow();
        }
        HttpResult inventory = call("GET", "/api/v1/inventory/SKU-HTTP-PRESSURE", token, null, null);
        assertThat(inventory.json().path("available").asInt()).isZero();
        assertThat(inventory.json().path("reserved").asInt()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE aggregate_type='ORDER'", Integer.class))
                .isEqualTo(10);
    }

    private String createUserAndLogin(Role role) throws Exception {
        String username = role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        users.insert(new UserAccount(UUID.randomUUID(), username, passwords.hash("role-password"),
                true, Set.of(role)));
        return login(username, "role-password");
    }

    private String login(String username, String password) throws Exception {
        HttpResult response = call("POST", "/api/v1/auth/token", null, null,
                Map.of("username", username, "password", password));
        assertThat(response.status).as(response.body).isEqualTo(200);
        assertThat(response.json().path("tokenType").asText()).isEqualTo("Bearer");
        return response.json().path("accessToken").asText();
    }

    private void assertError(HttpResult response, int status, String code) throws Exception {
        assertThat(response.status).as(response.body).isEqualTo(status);
        assertThat(response.json().path("code").asText()).isEqualTo(code);
        assertThat(response.body).doesNotContain("stacktrace", "password_hash", "accessToken");
    }

    private HttpResult call(String method, String path, String token, String key, Object body) throws Exception {
        return exchange(method, path, token, key, body == null ? null : mapper.writeValueAsString(body));
    }

    private HttpResult rawCall(String method, String path, String token, String key, String body) throws Exception {
        return exchange(method, path, token, key, body);
    }

    private HttpResult exchange(String method, String path, String token, String key, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        // Prometheus scrape is text/plain; JSON Accept would 406/404 the actuator mapping.
        if (path.startsWith("/actuator/prometheus")) {
            builder.header("Accept", "text/plain;version=0.0.4,*/*");
        } else {
            builder.header("Accept", "application/json");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body(), response);
    }

    private record HttpResult(int status, String body, HttpResponse<String> response) {
        private JsonNode json() throws Exception {
            return new ObjectMapper().readTree(body);
        }

        private String header(String name) {
            return response.headers().firstValue(name).orElse(null);
        }
    }
}

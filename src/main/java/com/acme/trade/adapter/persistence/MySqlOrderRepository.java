package com.acme.trade.adapter.persistence;

import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderFingerprint;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MySqlOrderRepository implements OrderRepository {
    private static final String COLUMNS =
            "id, request_id, sku, quantity, fingerprint, status, created_at, updated_at, expires_at, version";
    private static final RowMapper<Order> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new Order(
                OrderId.of(rs.getString("id")), new RequestId(rs.getString("request_id")), Sku.of(rs.getString("sku")),
                new Quantity(rs.getInt("quantity")), new OrderFingerprint(rs.getString("fingerprint")),
                OrderStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                expires == null ? null : expires.toInstant(),
                rs.getLong("version"));
    };

    private final JdbcTemplate jdbc;

    public MySqlOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Order> findByRequestId(RequestId requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE request_id = ?",
                ROW_MAPPER, requestId.value()).stream().findFirst();
    }

    @Override
    public Optional<Order> findById(OrderId orderId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE id = ?",
                ROW_MAPPER, orderId.toString()).stream().findFirst();
    }

    @Override
    public List<Order> findPage(OrderPageQuery query, int fetchLimit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM orders WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();
        if (query.status().isPresent()) {
            sql.append(" AND status = ?");
            arguments.add(query.status().orElseThrow().name());
        }
        if (query.sku().isPresent()) {
            sql.append(" AND sku = ?");
            arguments.add(query.sku().orElseThrow().value());
        }
        if (query.cursor().isPresent()) {
            var cursor = query.cursor().orElseThrow();
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            arguments.add(Timestamp.from(cursor.createdAt()));
            arguments.add(Timestamp.from(cursor.createdAt()));
            arguments.add(cursor.id().toString());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        arguments.add(fetchLimit);
        return jdbc.query(sql.toString(), ROW_MAPPER, arguments.toArray());
    }

    @Override
    public List<Order> findExpiredReserved(Instant now, int limit) {
        return jdbc.query("""
                SELECT %s FROM orders
                WHERE status = 'RESERVED' AND expires_at IS NOT NULL AND expires_at <= ?
                ORDER BY expires_at ASC, id ASC
                LIMIT ?
                """.formatted(COLUMNS),
                ROW_MAPPER, Timestamp.from(now), limit);
    }

    @Override
    public boolean transitionStatus(OrderId orderId, OrderStatus expected, OrderStatus target, Instant at) {
        return jdbc.update("""
                        UPDATE orders SET status = ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND status = ?
                        """, target.name(), Timestamp.from(at), orderId.toString(), expected.name()) == 1;
    }

    @Override
    public boolean reduceReservedQuantity(OrderId orderId, int expectedQuantity, int newQuantity, Instant at) {
        return jdbc.update("""
                        UPDATE orders SET quantity = ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND status = 'RESERVED' AND quantity = ?
                        """, newQuantity, Timestamp.from(at), orderId.toString(), expectedQuantity) == 1;
    }

    @Override
    public boolean updateExpiresAt(OrderId orderId, Instant expectedExpiresAt, Instant newExpiresAt, Instant at) {
        return jdbc.update("""
                        UPDATE orders SET expires_at = ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND status = 'RESERVED' AND expires_at = ?
                        """, Timestamp.from(newExpiresAt), Timestamp.from(at), orderId.toString(),
                Timestamp.from(expectedExpiresAt)) == 1;
    }

    @Override
    public void insert(Order order) {
        try {
            jdbc.update("INSERT INTO orders(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    order.id().toString(), order.requestId().value(), order.sku().value(), order.quantity().value(),
                    order.fingerprint().value(), order.status().name(), Timestamp.from(order.createdAt()),
                    Timestamp.from(order.updatedAt()),
                    order.expiresAt() == null ? null : Timestamp.from(order.expiresAt()),
                    order.version());
        } catch (DuplicateKeyException exception) {
            throw new DuplicateRequestException(order.requestId().value(), exception);
        }
    }
}

package com.acme.trade.adapter.persistence;

import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.port.OrderOperationRepository;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderOperation;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

public final class MySqlOrderOperationRepository implements OrderOperationRepository {
    private static final RowMapper<OrderOperation> MAPPER = (rs, rowNum) -> new OrderOperation(
            new RequestId(rs.getString("request_id")), OrderId.of(rs.getString("order_id")),
            OrderStatus.valueOf(rs.getString("target_status")), rs.getInt("effect_quantity"),
            rs.getString("reason"), rs.getString("actor_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public MySqlOrderOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<OrderOperation> findByRequestId(RequestId requestId) {
        return jdbc.query("""
                        SELECT request_id, order_id, target_status, effect_quantity, reason, actor_id, created_at
                        FROM order_operations WHERE request_id = ?
                        """, MAPPER, requestId.value())
                .stream().findFirst();
    }

    @Override
    public void insert(OrderOperation operation) {
        try {
            jdbc.update("""
                            INSERT INTO order_operations(request_id, order_id, target_status, effect_quantity,
                                                         reason, actor_id, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    operation.requestId().value(), operation.orderId().toString(),
                    operation.targetStatus().name(), operation.effectQuantity(),
                    operation.reason(), operation.actorId(), Timestamp.from(operation.createdAt()));
        } catch (DuplicateKeyException exception) {
            throw new DuplicateRequestException(operation.requestId().value(), exception);
        }
    }
}

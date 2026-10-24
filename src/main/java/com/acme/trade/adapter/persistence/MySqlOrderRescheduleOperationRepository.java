package com.acme.trade.adapter.persistence;

import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.port.OrderRescheduleOperationRepository;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderRescheduleOperation;
import com.acme.trade.domain.model.RequestId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

public final class MySqlOrderRescheduleOperationRepository implements OrderRescheduleOperationRepository {
    private static final RowMapper<OrderRescheduleOperation> MAPPER = (rs, rowNum) -> new OrderRescheduleOperation(
            new RequestId(rs.getString("request_id")), OrderId.of(rs.getString("order_id")),
            rs.getTimestamp("new_expires_at").toInstant(), rs.getString("reason"),
            rs.getString("actor_id"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public MySqlOrderRescheduleOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<OrderRescheduleOperation> findByRequestId(RequestId requestId) {
        return jdbc.query("""
                        SELECT request_id, order_id, new_expires_at, reason, actor_id, created_at
                        FROM order_reschedule_operations WHERE request_id = ?
                        """, MAPPER, requestId.value())
                .stream().findFirst();
    }

    @Override
    public void insert(OrderRescheduleOperation operation) {
        try {
            jdbc.update("""
                            INSERT INTO order_reschedule_operations(request_id, order_id, new_expires_at,
                                                                    reason, actor_id, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    operation.requestId().value(), operation.orderId().toString(),
                    Timestamp.from(operation.newExpiresAt()), operation.reason(),
                    operation.actorId(), Timestamp.from(operation.createdAt()));
        } catch (DuplicateKeyException exception) {
            throw new DuplicateRequestException(operation.requestId().value(), exception);
        }
    }
}

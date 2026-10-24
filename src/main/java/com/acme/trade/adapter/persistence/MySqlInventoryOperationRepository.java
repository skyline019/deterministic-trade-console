package com.acme.trade.adapter.persistence;

import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.port.InventoryOperationRepository;
import com.acme.trade.domain.model.InventoryOperation;
import com.acme.trade.domain.model.InventoryOperationType;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

public final class MySqlInventoryOperationRepository implements InventoryOperationRepository {
    private static final RowMapper<InventoryOperation> MAPPER = (rs, rowNum) -> new InventoryOperation(
            new RequestId(rs.getString("request_id")), Sku.of(rs.getString("sku")),
            InventoryOperationType.valueOf(rs.getString("operation_type")), rs.getInt("delta"),
            rs.getString("reason"), new StockLevel(rs.getInt("resulting_stock")), rs.getString("actor_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public MySqlInventoryOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<InventoryOperation> findByRequestId(RequestId requestId) {
        return jdbc.query("SELECT request_id, sku, operation_type, delta, reason, resulting_stock, actor_id, created_at "
                        + "FROM inventory_operations WHERE request_id = ?", MAPPER, requestId.value())
                .stream().findFirst();
    }

    @Override
    public void insert(InventoryOperation operation) {
        try {
            jdbc.update("""
                            INSERT INTO inventory_operations(request_id, sku, operation_type, delta, reason,
                                resulting_stock, actor_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """, operation.requestId().value(), operation.sku().value(), operation.type().name(),
                    operation.delta(), operation.reason(), operation.resultingStock().value(), operation.actorId(),
                    Timestamp.from(operation.createdAt()));
        } catch (DuplicateKeyException exception) {
            throw new DuplicateRequestException(operation.requestId().value(), exception);
        }
    }
}

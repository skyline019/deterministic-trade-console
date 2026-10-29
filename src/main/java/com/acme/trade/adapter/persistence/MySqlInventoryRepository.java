package com.acme.trade.adapter.persistence;

import com.acme.trade.application.port.InventoryRepository;
import com.acme.trade.domain.model.Inventory;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MySqlInventoryRepository implements InventoryRepository {
    private static final String COLUMNS = "sku, available_quantity, reserved_quantity, version, updated_at";
    private static final RowMapper<Inventory> ROW_MAPPER = (rs, rowNum) -> new Inventory(
            Sku.of(rs.getString("sku")), new StockLevel(rs.getInt("available_quantity")),
            new StockLevel(rs.getInt("reserved_quantity")), rs.getLong("version"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public MySqlInventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Inventory> find(Sku sku) {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory WHERE sku = ?", ROW_MAPPER, sku.value())
                .stream().findFirst();
    }

    @Override
    public List<Inventory> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory ORDER BY sku", ROW_MAPPER);
    }

    @Override
    public boolean create(Sku sku, StockLevel stock, Instant at) {
        try {
            jdbc.update("INSERT INTO inventory(" + COLUMNS + ") VALUES (?, ?, 0, 0, ?)",
                    sku.value(), stock.value(), Timestamp.from(at));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public boolean adjust(Sku sku, int delta, Instant at) {
        return jdbc.update("""
                        UPDATE inventory SET available_quantity = available_quantity + ?,
                            version = version + 1, updated_at = ?
                        WHERE sku = ? AND available_quantity + ? >= 0
                          AND available_quantity + ? + reserved_quantity <= """ + StockLevel.MAX,
                delta, Timestamp.from(at), sku.value(), delta, delta) == 1;
    }

    @Override
    public boolean tryReserve(Sku sku, Quantity quantity, Instant at) {
        return jdbc.update("""
                        UPDATE inventory
                        SET available_quantity = available_quantity - ?,
                            reserved_quantity = reserved_quantity + ?,
                            version = version + 1, updated_at = ?
                        WHERE sku = ? AND available_quantity >= ?
                          AND reserved_quantity + ? <= """ + StockLevel.MAX,
                quantity.value(), quantity.value(), Timestamp.from(at), sku.value(),
                quantity.value(), quantity.value()) == 1;
    }

    @Override
    public boolean releaseReservation(Sku sku, Quantity quantity, Instant at) {
        return jdbc.update("""
                        UPDATE inventory
                        SET available_quantity = available_quantity + ?,
                            reserved_quantity = reserved_quantity - ?,
                            version = version + 1, updated_at = ?
                        WHERE sku = ? AND reserved_quantity >= ?
                          AND available_quantity + ? <= """ + StockLevel.MAX,
                quantity.value(), quantity.value(), Timestamp.from(at), sku.value(),
                quantity.value(), quantity.value()) == 1;
    }

    @Override
    public boolean confirmReservation(Sku sku, Quantity quantity, Instant at) {
        return jdbc.update("""
                        UPDATE inventory SET reserved_quantity = reserved_quantity - ?,
                            version = version + 1, updated_at = ?
                        WHERE sku = ? AND reserved_quantity >= ?
                        """,
                quantity.value(), Timestamp.from(at), sku.value(), quantity.value()) == 1;
    }

    /**
     * Test/bootstrap seeding only (see the port contract). Fails closed when the row carries live
     * reservations, because zeroing {@code reserved_quantity} would break the dual-ledger invariant
     * that the order rows are still asserting.
     */
    @Override
    public void replaceStock(Sku sku, StockLevel stock, Instant at) {
        int replaced = jdbc.update("""
                        UPDATE inventory SET available_quantity = ?, version = version + 1, updated_at = ?
                        WHERE sku = ? AND reserved_quantity = 0
                        """, stock.value(), Timestamp.from(at), sku.value());
        if (replaced == 1) {
            return;
        }
        int created = jdbc.update("""
                        INSERT INTO inventory(sku, available_quantity, reserved_quantity, version, updated_at)
                        VALUES (?, ?, 0, 0, ?)
                        ON DUPLICATE KEY UPDATE version = version
                        """, sku.value(), stock.value(), Timestamp.from(at));
        if (created == 0) {
            throw new IllegalStateException("refusing to replace stock for " + sku.value()
                    + ": reserved_quantity is not zero");
        }
    }
}

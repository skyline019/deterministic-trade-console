package com.acme.trade.adapter.persistence;

import com.acme.trade.application.port.DashboardRepository;
import com.acme.trade.application.result.DashboardStats;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

public final class MySqlDashboardRepository implements DashboardRepository {
    private final JdbcTemplate jdbc;

    public MySqlDashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public DashboardStats load() {
        // One aggregate scan per fact table instead of seven independent scalar-subquery scans.
        return jdbc.queryForObject("""
                SELECT i.sku_count, i.available_stock, i.reserved_stock,
                       o.total_orders, o.reserved_orders, o.confirmed_orders, o.cancelled_orders
                FROM (
                    SELECT COUNT(*) AS sku_count,
                           COALESCE(SUM(available_quantity), 0) AS available_stock,
                           COALESCE(SUM(reserved_quantity), 0) AS reserved_stock
                    FROM inventory
                ) i
                CROSS JOIN (
                    SELECT COUNT(*) AS total_orders,
                           COALESCE(SUM(CASE WHEN status = 'RESERVED' THEN 1 ELSE 0 END), 0) AS reserved_orders,
                           COALESCE(SUM(CASE WHEN status = 'CONFIRMED' THEN 1 ELSE 0 END), 0) AS confirmed_orders,
                           COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled_orders
                    FROM orders
                ) o
                """, (rs, rowNum) -> new DashboardStats(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)));
    }
}

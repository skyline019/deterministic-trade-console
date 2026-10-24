package com.acme.trade.architecture;

import com.acme.trade.adapter.persistence.MySqlDashboardRepository;
import com.acme.trade.adapter.persistence.MySqlOrderRepository;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.application.result.DashboardStats;
import com.acme.trade.domain.model.OrderCursor;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Sku;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PersistencePerformanceContractTest {
    @Test
    void dashboard_uses_one_aggregate_scan_per_fact_table() {
        CapturingJdbc jdbc = new CapturingJdbc();
        DashboardStats result = new MySqlDashboardRepository(jdbc).load();

        assertThat(result.totalOrders()).isEqualTo(4);
        String sql = normalized(jdbc.sql);
        assertThat(occurrences(sql, "FROM INVENTORY")).isEqualTo(1);
        assertThat(occurrences(sql, "FROM ORDERS")).isEqualTo(1);
        assertThat(occurrences(sql, "SELECT")).isEqualTo(3);
        assertThat(sql).contains("CROSS JOIN").doesNotContain("OFFSET");
    }

    @Test
    void combined_order_filter_is_a_bounded_keyset_without_offset() {
        CapturingJdbc jdbc = new CapturingJdbc();
        MySqlOrderRepository repository = new MySqlOrderRepository(jdbc);
        OrderCursor cursor = new OrderCursor(Instant.parse("2026-01-01T00:00:00.123456789Z"),
                OrderId.of("00000000-0000-4000-8000-000000000001"));
        OrderPageQuery query = new OrderPageQuery(Optional.of(OrderStatus.RESERVED),
                Optional.of(Sku.of("SKU-PERFORMANCE")), Optional.of(cursor), 100);

        assertThat(repository.findPage(query, 101)).isEmpty();

        String sql = normalized(jdbc.sql);
        assertThat(sql).contains("STATUS = ?", "SKU = ?",
                "(CREATED_AT < ? OR (CREATED_AT = ? AND ID < ?))", "ORDER BY CREATED_AT DESC, ID DESC LIMIT ?");
        assertThat(sql).doesNotContain("OFFSET");
        assertThat(jdbc.arguments).contains(101);
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static final class CapturingJdbc extends JdbcTemplate {
        private String sql;
        private Object[] arguments = new Object[0];

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper) {
            this.sql = sql;
            return (T) new DashboardStats(1, 2, 3, 4, 5, 6, 7);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return List.of();
        }
    }
}

package com.acme.trade.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaContractTest {
    @Test
    void stock_and_order_invariants_are_also_database_constraints() throws IOException {
        String schema = schema();
        assertThat(schema).contains("available_quantity >= 0", "reserved_quantity >= 0",
                "available_quantity + reserved_quantity <= 100000000",
                "quantity > 0", "uk_orders_request_id UNIQUE (request_id)",
                "request_id VARCHAR(128) NOT NULL PRIMARY KEY");
        assertThat(schema).contains("fk_orders_inventory_sku", "fk_order_operations_order");
    }

    @Test
    void keyset_and_combined_filter_indexes_cover_every_query_shape() throws IOException {
        String schema = schema();
        assertThat(schema).contains("INDEX idx_orders_cursor (created_at, id)",
                "INDEX idx_orders_status_cursor (status, created_at, id)",
                "INDEX idx_orders_sku_cursor (sku, created_at, id)",
                "INDEX idx_orders_status_sku_cursor (status, sku, created_at, id)");
        assertThat(schema).doesNotContain("OFFSET");
    }

    @Test
    void audit_uniqueness_is_per_aggregate_not_a_global_sequence_lock() throws IOException {
        String schema = schema();
        assertThat(schema).contains("uk_audit_aggregate_sequence UNIQUE (aggregate_type, aggregate_id, sequence)",
                "chk_audit_sequence CHECK (sequence > 0)", "payload_json MEDIUMTEXT NOT NULL",
                "previous_hash CHAR(64) NOT NULL", "event_hash CHAR(64) NOT NULL");
        assertThat(schema).doesNotContain("sequence BIGINT NOT NULL UNIQUE");
    }

    @Test
    void only_token_hashes_are_persisted_and_expiry_is_indexed() throws IOException {
        String schema = schema();
        assertThat(schema).contains("token_hash CHAR(64) NOT NULL PRIMARY KEY",
                "INDEX idx_api_tokens_expiry (expires_at)", "password_hash VARCHAR(72) NOT NULL");
        assertThat(schema).doesNotContain("raw_token", "access_token VARCHAR", "password VARCHAR");
    }

    @Test
    void flyway_v2_adds_archive_without_mutating_live_append_semantics() throws IOException {
        String archive = resource("db/migration/V2__audit_events_archive.sql");
        assertThat(archive).contains("CREATE TABLE audit_events_archive",
                "archived_at TIMESTAMP(6) NOT NULL",
                "uk_audit_archive_aggregate_sequence UNIQUE (aggregate_type, aggregate_id, sequence)");
        assertThat(archive).doesNotContain("DELETE FROM audit_events", "UPDATE audit_events");
    }

    @Test
    void migration_versions_are_contiguous_and_forward_only() throws IOException, URISyntaxException {
        List<String> files = migrationFiles();
        List<Integer> versions = files.stream().map(SchemaContractTest::versionOf).toList();
        // A gap means a version that was renumbered or deleted after being shipped: Flyway would
        // either skip it or fail on an unrelated database, and no other test would notice.
        assertThat(versions).isSorted();
        assertThat(versions).doesNotHaveDuplicates();
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i))
                    .as("migration chain must start at V1 and have no gaps: %s", files)
                    .isEqualTo(i + 1);
        }
        assertThat(versions.size())
                .as("the archive scan index (V6) must remain part of the chain")
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void no_migration_rewrites_audit_history_it_can_only_extend_the_schema()
            throws IOException, URISyntaxException {
        for (String migrate : migrationFiles()) {
            String sql = resource("db/migration/" + migrate);
            assertThat(sql.replaceAll("(?m)^\\s*--.*$", "").toLowerCase())
                    .as("%s must not mutate or drop audit rows; cold storage is an adapter concern", migrate)
                    .doesNotContain("delete from audit_events", "update audit_events", "drop table audit_events");
        }
    }

    private static List<String> migrationFiles() throws IOException, URISyntaxException {
        var url = Thread.currentThread().getContextClassLoader().getResource("db/migration/V1__baseline_schema.sql");
        assertThat(url).as("V1 must be on the test classpath").isNotNull();
        Path directory = Path.of(url.toURI()).getParent();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    // Numeric order: a future V10 must not sort before V2.
                    .sorted(Comparator.comparingInt(SchemaContractTest::versionOf))
                    .toList();
        }
    }

    private static int versionOf(String fileName) {
        Matcher matcher = Pattern.compile("^V(\\d+)__.*\\.sql$").matcher(fileName);
        assertThat(matcher.matches()).as("unexpected migration name: %s", fileName).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static String schema() throws IOException {
        return resource("db/migration/V1__baseline_schema.sql");
    }

    private static String resource(String path) throws IOException {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

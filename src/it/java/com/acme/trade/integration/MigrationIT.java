package com.acme.trade.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the migration chain itself. {@code HighConcurrencyIT} proves the ledger under contention,
 * but it starts from whatever Flyway produces; this IT proves Flyway produces the documented surface
 * (V1..Vn, the archive table, the expiry column, the receipt column, the archive scan index) and that
 * an ambiguous database fails closed instead of being silently adopted.
 *
 * <p>Run explicitly with {@code ./mvnw -Pintegration verify} (Docker required); CI runs it.
 */
class MigrationIT {
    private static final DockerImageName MYSQL = DockerImageName.parse("mysql:8.4");
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Test
    void fresh_database_applies_every_migration_and_exposes_the_documented_surface() throws Exception {
        try (MySQLContainer<?> mysql = newContainer("trade")) {
            mysql.start();

            MigrateResult result = flyway(mysql, false).migrate();

            assertThat(result.migrationsExecuted)
                    .as("every migration in db/migration must be applied on a clean database")
                    .isGreaterThanOrEqualTo(7);
            assertThat(migratedVersions(mysql))
                    .containsSubsequence("1", "2", "3", "4", "5", "6", "7");

            assertThat(columnExists(mysql, "orders", "expires_at"))
                    .as("V3 adds the reservation deadline (INV-013 depends on it)")
                    .isTrue();
            assertThat(columnExists(mysql, "order_operations", "effect_quantity"))
                    .as("V4 adds the partial-confirm effect quantity (ADR 002)")
                    .isTrue();
            assertThat(tableExists(mysql, "audit_events_archive"))
                    .as("V2 adds cold storage for quiet chains (INV-014)")
                    .isTrue();
            assertThat(tableExists(mysql, "order_reschedule_operations"))
                    .as("V5 adds the reschedule receipt namespace (ADR 003)")
                    .isTrue();
            assertThat(indexExists(mysql, "audit_events", "idx_audit_aggregate_occurred"))
                    .as("V6 adds the archive-eligibility scan index")
                    .isTrue();
            assertThat(indexExists(mysql, "orders", "idx_orders_expire_reserved"))
                    .as("V3 adds the expiry sweep index")
                    .isTrue();
            assertThat(checkConstraintExists(mysql, "orders", "chk_orders_updated_after_created"))
                    .as("V7 makes a non-monotonic row unwritable instead of merely unreadable")
                    .isTrue();
            assertThat(checkConstraintExists(mysql, "orders", "chk_orders_reserved_expiry"))
                    .as("V7 rejects a reservation that expires before it was created")
                    .isTrue();

            // Re-running must be a no-op: migrations are forward-only and never re-applied.
            assertThat(flyway(mysql, false).migrate().migrationsExecuted)
                    .as("a second migrate must not execute anything")
                    .isZero();
        }
    }

    /**
     * The domain refuses to materialise these rows, so a stored one becomes a 500 (STORED_STATE_INVALID) on
     * read and an unreadable report on the expiry sweep. V7 moves the check to the writer: the same values
     * that the constructor rejects must now fail the INSERT.
     */
    @Test
    void v7_rejects_the_rows_the_domain_would_refuse_to_materialise() throws Exception {
        try (MySQLContainer<?> mysql = newContainer("trade")) {
            mysql.start();
            flyway(mysql, false).migrate();

            try (Connection connection = connection(mysql); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO inventory(sku, available_quantity, reserved_quantity, version, updated_at)"
                        + " VALUES ('SKU-V7', 10, 0, 0, NOW(6))");

                assertThatThrownBy(() -> statement.execute(orderInsert(
                        "v7-expiry-before-created", "RESERVED",
                        "NOW(6), NOW(6), NOW(6) - INTERVAL 1 MINUTE")))
                        .as("a RESERVED order expiring before it was created must never reach the table")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_orders_reserved_expiry");

                assertThatThrownBy(() -> statement.execute(orderInsert(
                        "v7-null-expiry", "RESERVED", "NOW(6), NOW(6), NULL")))
                        .as("a RESERVED order without a deadline must never reach the table")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_orders_reserved_expiry");

                assertThatThrownBy(() -> statement.execute(orderInsert(
                        "v7-updated-before-created", "CANCELLED",
                        "NOW(6), NOW(6) - INTERVAL 1 MINUTE, NULL")))
                        .as("updated_at < created_at must never reach the table")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_orders_updated_after_created");

                // The constraints bound corruption, not normal writes: a healthy reservation still inserts,
                // and a terminal order legitimately drops its deadline.
                statement.execute(orderInsert("v7-valid-reserved", "RESERVED",
                        "NOW(6), NOW(6), NOW(6) + INTERVAL 1 HOUR"));
                statement.execute(orderInsert("v7-valid-cancelled", "CANCELLED", "NOW(6), NOW(6), NULL"));
                assertThat(count(mysql, "SELECT COUNT(*) FROM orders WHERE request_id IN (?, ?)",
                        "v7-valid-reserved", "v7-valid-cancelled"))
                        .as("V7 must not reject rows the domain accepts")
                        .isEqualTo(2);
            }
        }
    }

    private static String orderInsert(String requestId, String status, String timestamps) {
        return "INSERT INTO orders(id, request_id, sku, quantity, fingerprint, status, created_at, updated_at,"
                + " expires_at, version) VALUES (UUID(), '" + requestId + "', 'SKU-V7', 1, REPEAT('a', 64), '"
                + status + "', " + timestamps + ", 0)";
    }

    @Test
    void non_empty_schema_without_history_fails_closed_until_baseline_is_explicit() throws Exception {
        try (MySQLContainer<?> mysql = newContainer("trade")) {
            mysql.start();
            // Simulates a pre-Flyway or restored volume: tables exist, flyway_schema_history does not.
            try (Connection connection = connection(mysql); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stray_legacy_table (id INT PRIMARY KEY)");
            }

            assertThatThrownBy(() -> flyway(mysql, false).migrate())
                    .as("baseline-on-migrate=false must refuse to adopt an unknown schema")
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("baseline");
            assertThat(tableExists(mysql, "flyway_schema_history"))
                    .as("a refused migration must not leave partial history behind")
                    .isFalse();
            assertThat(tableExists(mysql, "audit_events"))
                    .as("a refused migration must not create business tables")
                    .isFalse();
        }
    }

    @Test
    void explicit_baseline_adopts_a_legacy_database_that_already_satisfies_v1() throws Exception {
        try (MySQLContainer<?> mysql = newContainer("trade")) {
            mysql.start();
            // A real legacy database: it already has the V1 surface, just no Flyway history.
            // Baselining at V1 tells Flyway to skip V1, so V1's tables must already exist —
            // this is exactly the assumption operators take on when they set the flag.
            try (Connection connection = connection(mysql)) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/V1__baseline_schema.sql"));
            }

            MigrateResult baselined = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations(MIGRATION_LOCATION)
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load()
                    .migrate();

            assertThat(baselined.migrationsExecuted)
                    .as("baseline at V1 then apply V2..Vn (V1 is assumed present, not re-run)")
                    .isGreaterThanOrEqualTo(5);
            assertThat(migratedVersions(mysql)).containsSubsequence("1", "2", "3", "4", "5", "6");
            assertThat(columnExists(mysql, "orders", "expires_at")).isTrue();
            assertThat(tableExists(mysql, "audit_events_archive")).isTrue();
        }
    }

    @Test
    void baselining_at_v1_without_the_v1_surface_fails_instead_of_producing_a_broken_schema() throws Exception {
        try (MySQLContainer<?> mysql = newContainer("trade")) {
            mysql.start();
            // The trap operators hit: an unrelated legacy table plus baseline-on-migrate=true is
            // accepted, V1 is skipped, and the first dependent migration fails. Pin that behaviour
            // so the runbook keeps telling people to verify the V1 surface before baselining.
            try (Connection connection = connection(mysql); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stray_legacy_table (id INT PRIMARY KEY)");
            }

            assertThatThrownBy(() -> Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations(MIGRATION_LOCATION)
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load()
                    .migrate())
                    .as("skipping V1 without its tables must surface as a migration failure, not a silent gap")
                    .isInstanceOf(FlywayException.class);
        }
    }

    private static MySQLContainer<?> newContainer(String database) {
        return new MySQLContainer<>(MYSQL)
                .withDatabaseName(database)
                .withUsername("trade")
                .withPassword("trade");
    }

    private static Flyway flyway(MySQLContainer<?> mysql, boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations(MIGRATION_LOCATION)
                .baselineOnMigrate(baselineOnMigrate)
                .load();
    }

    private static Connection connection(MySQLContainer<?> mysql) throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static List<String> migratedVersions(MySQLContainer<?> mysql) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection connection = connection(mysql);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank")) {
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
        }
        return versions;
    }

    private static boolean tableExists(MySQLContainer<?> mysql, String table) throws SQLException {
        return count(mysql, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = ?", table) > 0;
    }

    private static boolean columnExists(MySQLContainer<?> mysql, String table, String column) throws SQLException {
        return count(mysql, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?", table, column) > 0;
    }

    private static boolean checkConstraintExists(MySQLContainer<?> mysql, String table, String constraint)
            throws SQLException {
        return count(mysql, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND constraint_name = ? "
                + "AND constraint_type = 'CHECK'", table, constraint) > 0;
    }

    private static boolean indexExists(MySQLContainer<?> mysql, String table, String index) throws SQLException {
        return count(mysql, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?", table, index) > 0;
    }

    private static long count(MySQLContainer<?> mysql, String sql, String... parameters) throws SQLException {
        try (Connection connection = connection(mysql);
             var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }
}

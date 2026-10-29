package com.acme.trade.adapter.persistence;

import com.acme.trade.application.port.MaintenanceStore;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.audit.AuditChainVerifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class MySqlMaintenanceStore implements MaintenanceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MySqlMaintenanceStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public int deleteExpiredTokens(Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        Integer deleted = transactions.execute(status -> jdbc.update("""
                DELETE FROM api_tokens
                WHERE token_hash IN (
                    SELECT token_hash FROM (
                        SELECT token_hash FROM api_tokens WHERE expires_at <= ? LIMIT ?
                    ) expired
                )
                """, Timestamp.from(now), limit));
        return deleted == null ? 0 : deleted;
    }

    /**
     * Copies and deletes exactly the same bounded row set: {@code sequence <= tip} is resolved before
     * the copy, so an event appended to a "quiet" aggregate in between stays live instead of being
     * deleted without ever reaching the archive table.
     */
    @Override
    public int archiveQuietAggregates(Instant cutoff, Instant archivedAt, int maxAggregates) {
        if (maxAggregates < 1) {
            throw new IllegalArgumentException("maxAggregates must be >= 1");
        }
        Integer archived = transactions.execute(status -> {
            List<AggregateKey> quiet = jdbc.query("""
                    SELECT aggregate_type, aggregate_id, MAX(sequence) AS tip_sequence
                    FROM audit_events
                    GROUP BY aggregate_type, aggregate_id
                    HAVING MAX(occurred_at) < ?
                    ORDER BY MAX(occurred_at) ASC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new AggregateKey(
                            rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getLong("tip_sequence")),
                    Timestamp.from(cutoff), maxAggregates);
            int count = 0;
            Timestamp archivedTs = Timestamp.from(archivedAt);
            for (AggregateKey key : quiet) {
                jdbc.update("""
                        INSERT INTO audit_events_archive(
                            id, aggregate_type, aggregate_id, sequence, action, actor_id, actor_name,
                            request_id, occurred_at, payload_json, previous_hash, event_hash, archived_at)
                        SELECT id, aggregate_type, aggregate_id, sequence, action, actor_id, actor_name,
                               request_id, occurred_at, payload_json, previous_hash, event_hash, ?
                        FROM audit_events
                        WHERE aggregate_type = ? AND aggregate_id = ? AND sequence <= ?
                        """, archivedTs, key.type(), key.id(), key.tipSequence());
                jdbc.update("""
                        DELETE FROM audit_events
                        WHERE aggregate_type = ? AND aggregate_id = ? AND sequence <= ?
                        """, key.type(), key.id(), key.tipSequence());
                count++;
            }
            return count;
        });
        return archived == null ? 0 : archived;
    }

    @Override
    public List<AggregateRef> listCheckpointCandidates(int minEventsSinceCheckpoint, int maxAggregates) {
        if (minEventsSinceCheckpoint < 1 || maxAggregates < 1) {
            throw new IllegalArgumentException("checkpoint listing bounds must be >= 1");
        }
        return jdbc.query("""
                SELECT tip.aggregate_type, tip.aggregate_id
                FROM (
                    SELECT aggregate_type, aggregate_id, MAX(sequence) AS tip_seq
                    FROM audit_events
                    GROUP BY aggregate_type, aggregate_id
                    HAVING MAX(sequence) >= ?
                ) tip
                LEFT JOIN (
                    SELECT aggregate_type, aggregate_id, MAX(sequence) AS checkpoint_seq
                    FROM audit_events
                    WHERE action = ?
                    GROUP BY aggregate_type, aggregate_id
                ) cp ON tip.aggregate_type = cp.aggregate_type AND tip.aggregate_id = cp.aggregate_id
                WHERE cp.checkpoint_seq IS NULL
                   OR (tip.tip_seq - cp.checkpoint_seq) >= ?
                ORDER BY tip.tip_seq DESC
                LIMIT ?
                """,
                (rs, rowNum) -> AggregateRef.of(
                        AggregateType.valueOf(rs.getString("aggregate_type")),
                        rs.getString("aggregate_id")),
                minEventsSinceCheckpoint,
                AuditChainVerifier.CHECKPOINT_ACTION,
                minEventsSinceCheckpoint,
                maxAggregates);
    }

    private record AggregateKey(String type, String id, long tipSequence) {
    }
}

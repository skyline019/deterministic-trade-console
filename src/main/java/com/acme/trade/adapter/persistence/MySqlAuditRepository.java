package com.acme.trade.adapter.persistence;

import com.acme.trade.application.port.AuditRepository;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.audit.AuditEvent;
import com.acme.trade.domain.model.RequestId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MySqlAuditRepository implements AuditRepository {
    private static final String COLUMNS = "id, aggregate_type, aggregate_id, sequence, action, actor_id, "
            + "actor_name, request_id, occurred_at, payload_json, previous_hash, event_hash";
    private static final RowMapper<AuditEvent> MAPPER = (rs, rowNum) -> new AuditEvent(
            UUID.fromString(rs.getString("id")),
            new AggregateRef(AggregateType.valueOf(rs.getString("aggregate_type")), rs.getString("aggregate_id")),
            rs.getLong("sequence"), rs.getString("action"), rs.getString("actor_id"),
            rs.getString("actor_name"), new RequestId(rs.getString("request_id")),
            rs.getTimestamp("occurred_at").toInstant(), rs.getString("payload_json"),
            rs.getString("previous_hash"), rs.getString("event_hash"));

    private final JdbcTemplate jdbc;

    public MySqlAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<AuditEvent> findLast(AggregateRef aggregate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_events "
                        + "WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY sequence DESC LIMIT 1",
                MAPPER, aggregate.type().name(), aggregate.id()).stream().findFirst();
    }

    @Override
    public List<AuditEvent> findChain(AggregateRef aggregate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_events "
                        + "WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY sequence ASC",
                MAPPER, aggregate.type().name(), aggregate.id());
    }

    @Override
    public List<AuditEvent> findRecent(AggregateRef aggregate, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM (SELECT " + COLUMNS + " FROM audit_events "
                        + "WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY sequence DESC LIMIT ?) recent "
                        + "ORDER BY sequence ASC",
                MAPPER, aggregate.type().name(), aggregate.id(), limit);
    }

    @Override
    public List<AuditEvent> findFromSequence(AggregateRef aggregate, long fromSequenceInclusive) {
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_events "
                        + "WHERE aggregate_type = ? AND aggregate_id = ? AND sequence >= ? ORDER BY sequence ASC",
                MAPPER, aggregate.type().name(), aggregate.id(), fromSequenceInclusive);
    }

    @Override
    public Optional<AuditEvent> findByEventHash(AggregateRef aggregate, String eventHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_events "
                        + "WHERE aggregate_type = ? AND aggregate_id = ? AND event_hash = ? LIMIT 1",
                MAPPER, aggregate.type().name(), aggregate.id(), eventHash).stream().findFirst();
    }

    @Override
    public void insert(AuditEvent event) {
        jdbc.update("INSERT INTO audit_events(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.id().toString(), event.aggregate().type().name(), event.aggregate().id(), event.sequence(),
                event.action(), event.actorId(), event.actorName(), event.requestId().value(),
                Timestamp.from(event.occurredAt()), event.payloadJson(), event.previousHash(), event.eventHash());
    }
}

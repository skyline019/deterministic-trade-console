package com.acme.trade.application.port;

import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AuditEvent;

import java.util.List;
import java.util.Optional;

public interface AuditRepository {
    Optional<AuditEvent> findLast(AggregateRef aggregate);

    List<AuditEvent> findChain(AggregateRef aggregate);

    List<AuditEvent> findRecent(AggregateRef aggregate, int limit);

    /** Inclusive lower bound; used for checkpoint-anchored verification. */
    List<AuditEvent> findFromSequence(AggregateRef aggregate, long fromSequenceInclusive);

    Optional<AuditEvent> findByEventHash(AggregateRef aggregate, String eventHash);

    /** Append-only by contract: this port intentionally exposes no update or delete operation. */
    void insert(AuditEvent event);
}

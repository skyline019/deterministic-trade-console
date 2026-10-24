package com.acme.trade.application.port;

import com.acme.trade.domain.audit.AggregateRef;

import java.time.Instant;
import java.util.List;

/**
 * Explicit maintenance mutations that must not appear on the append-only {@link AuditRepository}.
 * Invoked only by one-shot runners (never {@code @Scheduled}).
 */
public interface MaintenanceStore {
    /** Deletes expired bearer token hashes. Returns rows removed in this batch. */
    int deleteExpiredTokens(Instant now, int limit);

    /**
     * Moves entire quiet aggregate chains (MAX(occurred_at) &lt; cutoff) into archive, then deletes
     * them from the live table. Preserves hash-chain integrity; never archives a partial chain.
     *
     * @return number of aggregates archived in this batch
     */
    int archiveQuietAggregates(Instant cutoff, Instant archivedAt, int maxAggregates);

    /**
     * Live aggregates whose tip is at least {@code minEventsSinceCheckpoint} events past the last
     * {@code CHAIN_CHECKPOINT} (or past genesis when none exists).
     */
    List<AggregateRef> listCheckpointCandidates(int minEventsSinceCheckpoint, int maxAggregates);
}

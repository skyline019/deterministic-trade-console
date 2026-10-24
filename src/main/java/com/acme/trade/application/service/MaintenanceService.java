package com.acme.trade.application.service;

import com.acme.trade.application.port.MaintenanceStore;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One-shot operational commands. No background scheduling. */
public final class MaintenanceService {
    /** Synthetic actor for ops-authored checkpoint events; roles empty (record path has no RBAC). */
    private static final Actor MAINTENANCE_ACTOR = new Actor("maintenance", "maintenance", Set.of());
    /** Expiry cancels via the same transition path; needs ORDER_WRITE. */
    private static final Actor EXPIRY_ACTOR =
            new Actor("system-expiry", "system-expiry", EnumSet.of(Role.OPERATOR));
    private static final String EXPIRE_REASON = "reservation-expired";

    private final MaintenanceStore store;
    private final AuditService audits;
    private final OrderRepository orders;
    private final OrderTransitionService transitions;
    private final Clock clock;

    public MaintenanceService(MaintenanceStore store, AuditService audits, OrderRepository orders,
                              OrderTransitionService transitions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audits = Objects.requireNonNull(audits, "audits");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int purgeExpiredTokens(int batchSize) {
        requireBatch(batchSize);
        return store.deleteExpiredTokens(clock.instant(), batchSize);
    }

    public int archiveQuietAudits(Duration quietFor, int maxAggregates) {
        requireBatch(maxAggregates);
        if (quietFor == null || quietFor.isNegative() || quietFor.isZero()) {
            throw new ValidationException("audit quiet duration must be positive");
        }
        Instant now = clock.instant();
        return store.archiveQuietAggregates(now.minus(quietFor), now, maxAggregates);
    }

    /**
     * Appends {@code CHAIN_CHECKPOINT} events for long live chains so auditors can verify
     * suffixes against an out-of-band attested checkpoint hash.
     *
     * @return number of checkpoints written in this batch
     */
    public int checkpointLongChains(int minEventsSinceCheckpoint, int maxAggregates) {
        requireBatch(maxAggregates);
        if (minEventsSinceCheckpoint < 10 || minEventsSinceCheckpoint > 1_000_000) {
            throw new ValidationException("checkpoint spacing must be between 10 and 1000000");
        }
        List<AggregateRef> candidates = store.listCheckpointCandidates(minEventsSinceCheckpoint, maxAggregates);
        int written = 0;
        for (AggregateRef aggregate : candidates) {
            RequestId requestId = new RequestId("checkpoint-" + UUID.randomUUID());
            audits.appendCheckpoint(MAINTENANCE_ACTOR, aggregate, requestId);
            written++;
        }
        return written;
    }

    /**
     * Cancels expired RESERVED orders through {@link OrderTransitionService} (same ledger as manual cancel).
     * Idempotent per order via request id {@code expire-{orderId}}.
     *
     * @return number of expire transitions attempted successfully (including replays)
     */
    public int expireReservations(int batchSize) {
        requireBatch(batchSize);
        Instant now = clock.instant();
        List<Order> expired = orders.findExpiredReserved(now, batchSize);
        int affected = 0;
        for (Order order : expired) {
            RequestId requestId = new RequestId("expire-" + order.id());
            transitions.transition(EXPIRY_ACTOR, requestId, order.id(), OrderStatus.CANCELLED, EXPIRE_REASON);
            affected++;
        }
        return affected;
    }

    private static void requireBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new ValidationException("maintenance batch size must be between 1 and 10000");
        }
    }
}

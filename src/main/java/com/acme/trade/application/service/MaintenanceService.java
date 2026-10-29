package com.acme.trade.application.service;

import com.acme.trade.application.port.ExpiredReservationScan;
import com.acme.trade.application.port.MaintenanceStore;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.result.SweepReport;
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
import java.util.ArrayList;
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
     * suffixes against an out-of-band attested checkpoint hash. A chain that moved between the
     * candidate query and the append is reported as a failure instead of aborting the batch.
     */
    public SweepReport checkpointLongChains(int minEventsSinceCheckpoint, int maxAggregates) {
        requireBatch(maxAggregates);
        if (minEventsSinceCheckpoint < 10 || minEventsSinceCheckpoint > 1_000_000) {
            throw new ValidationException("checkpoint spacing must be between 10 and 1000000");
        }
        List<AggregateRef> candidates = store.listCheckpointCandidates(minEventsSinceCheckpoint, maxAggregates);
        List<String> failures = new ArrayList<>();
        int written = 0;
        for (AggregateRef aggregate : candidates) {
            RequestId requestId = new RequestId("checkpoint-" + UUID.randomUUID());
            try {
                audits.appendCheckpoint(MAINTENANCE_ACTOR, aggregate, requestId);
                written++;
            } catch (RuntimeException failure) {
                failures.add(aggregate.type() + "/" + aggregate.id() + ": " + describe(failure));
            }
        }
        return new SweepReport(candidates.size(), written, failures);
    }

    /**
     * Cancels expired RESERVED orders through {@link OrderTransitionService} (same ledger as manual cancel).
     * Idempotent per order via request id {@code expire-{orderId}}.
     *
     * <p>Per-order isolation is deliberate: an order confirmed or cancelled between
     * {@code findExpiredReserved} and the transition is an expected race, and must not keep the rest of
     * the batch reserved. Failures are reported, not thrown, so a cron job keeps making progress.
     * Rows the scan could not materialise are reported the same way — a corrupt row must not keep every
     * other reservation alive.
     */
    public SweepReport expireReservations(int batchSize) {
        requireBatch(batchSize);
        Instant now = clock.instant();
        ExpiredReservationScan scan = orders.findExpiredReserved(now, batchSize);
        List<String> failures = new ArrayList<>(scan.unreadable());
        int affected = 0;
        for (Order order : scan.orders()) {
            RequestId requestId = new RequestId("expire-" + order.id());
            try {
                transitions.transition(EXPIRY_ACTOR, requestId, order.id(), OrderStatus.CANCELLED, EXPIRE_REASON);
                affected++;
            } catch (RuntimeException failure) {
                failures.add(order.id() + ": " + describe(failure));
            }
        }
        return new SweepReport(scan.selected(), affected, failures);
    }

    /** Bounded, payload-free description: exception type only (no SQL, tokens or request bodies). */
    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String type = failure.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + " (" + message + ")";
    }

    private static void requireBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new ValidationException("maintenance batch size must be between 1 and 10000");
        }
    }
}

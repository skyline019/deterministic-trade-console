package com.acme.trade.application.result;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a per-item maintenance sweep. A sweep degrades instead of aborting: one item that lost a
 * race (state conflict, duplicate receipt, insufficient stock) must not stop the remaining items,
 * otherwise a single hot order can keep a reservation-expiry or checkpoint run from ever finishing.
 *
 * @param attempted items the store selected for this batch
 * @param applied   items whose effect committed (including idempotent replays)
 * @param failures  human-readable, bounded {@code item: reason} descriptions; never partial payloads
 */
public record SweepReport(int attempted, int applied, List<String> failures) {
    public SweepReport {
        if (attempted < 0 || applied < 0 || applied > attempted) {
            throw new IllegalArgumentException("sweep counts must satisfy 0 <= applied <= attempted");
        }
        Objects.requireNonNull(failures, "failures");
        failures = List.copyOf(failures);
    }

    public int failed() {
        return failures.size();
    }

    /** True when the batch completed for every selected item. */
    public boolean complete() {
        return failures.isEmpty();
    }
}

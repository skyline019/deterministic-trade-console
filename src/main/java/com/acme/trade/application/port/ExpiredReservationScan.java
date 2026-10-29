package com.acme.trade.application.port;

import com.acme.trade.domain.model.Order;

import java.util.List;
import java.util.Objects;

/**
 * Result of scanning for expired reservations.
 *
 * <p>A one-shot sweep must make progress over the rows it can read, so a row that cannot be
 * materialised (corrupt/invariant-violating state written before the schema enforced it) is
 * reported instead of thrown: the alternative is that a single bad row keeps every other
 * reservation alive forever, with nobody watching a cron job.
 *
 * @param orders     rows that mapped cleanly and are eligible for expiry
 * @param unreadable bounded, payload-free descriptions of rows that could not be mapped
 */
public record ExpiredReservationScan(List<Order> orders, List<String> unreadable) {
    public ExpiredReservationScan {
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(unreadable, "unreadable");
        orders = List.copyOf(orders);
        unreadable = List.copyOf(unreadable);
    }

    /** Every row the store selected for this batch (readable or not). */
    public int selected() {
        return orders.size() + unreadable.size();
    }
}

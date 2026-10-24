package com.acme.trade.adapter.web;

import java.time.Instant;

/** Body for {@code POST .../reschedule}. */
public record OrderRescheduleHttpRequest(Instant expiresAt, String reason) {
}

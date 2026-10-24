package com.acme.trade.config;

import java.time.Duration;

public record TradeSettings(
        Duration lockTtl,
        int transactionTimeoutSeconds,
        int lockRetryAttempts,
        Duration lockRetryBackoff,
        Duration reservationTtl) {
    public TradeSettings {
        if (lockTtl == null || lockTtl.isNegative() || lockTtl.isZero()) {
            throw new IllegalArgumentException("trade.lock-ttl must be positive");
        }
        if (transactionTimeoutSeconds < 1) {
            throw new IllegalArgumentException("trade.transaction-timeout-seconds must be at least 1");
        }
        if (lockTtl.compareTo(Duration.ofSeconds(transactionTimeoutSeconds)) <= 0) {
            throw new IllegalArgumentException("trade.lock-ttl must be greater than the transaction timeout");
        }
        if (lockRetryAttempts < 1 || lockRetryAttempts > 20) {
            throw new IllegalArgumentException("trade.lock-retry-attempts must be between 1 and 20");
        }
        if (lockRetryBackoff == null || lockRetryBackoff.isNegative()) {
            throw new IllegalArgumentException("trade.lock-retry-backoff must be non-negative");
        }
        if (reservationTtl == null || reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("trade.reservation-ttl must be positive");
        }
        if (reservationTtl.compareTo(Duration.ofMinutes(1)) < 0
                || reservationTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("trade.reservation-ttl must be between 1m and 7d");
        }
    }
}

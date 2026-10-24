package com.acme.trade.adapter.redis;

import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.port.LockHandle;
import com.acme.trade.application.port.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded retry around Redis I/O failures only. Contention ({@link Optional#empty()}) is not retried.
 * Still fail-closed after the budget — never silently skips the lock.
 */
public final class RetryingLockManager implements LockManager {
    private static final Logger log = LoggerFactory.getLogger(RetryingLockManager.class);

    private final LockManager delegate;
    private final int maxAttempts;
    private final Duration backoff;

    public RetryingLockManager(LockManager delegate, int maxAttempts, Duration backoff) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("lock retry attempts must be between 1 and 20");
        }
        if (backoff == null || backoff.isNegative()) {
            throw new IllegalArgumentException("lock retry backoff must be non-negative");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return delegate.tryAcquire(key, ttl);
            } catch (LockUnavailableException exception) {
                if (attempt >= maxAttempts) {
                    throw exception;
                }
                log.warn("Redis lock I/O failed for {} (attempt {}/{}); retrying", key, attempt, maxAttempts);
                sleep(backoff.multipliedBy(attempt));
            }
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LockUnavailableException("Redis lock retry interrupted", interrupted);
        }
    }
}

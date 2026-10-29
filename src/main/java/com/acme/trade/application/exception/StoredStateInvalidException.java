package com.acme.trade.application.exception;

/**
 * A persisted row cannot be materialised into a domain object: the stored state violates an
 * invariant the domain enforces (for example a RESERVED order whose {@code expires_at} is not after
 * {@code created_at}).
 *
 * <p>This is deliberately distinct from {@link com.acme.trade.domain.exception.ValidationException}:
 * on a read path the caller supplied nothing, so blaming the request with {@code 400 INVALID_REQUEST}
 * is wrong. It means "the server's own data is broken" and maps to {@code 500 STORED_STATE_INVALID}.
 * Maintenance sweeps treat it as a per-row failure instead of aborting the batch.
 */
public final class StoredStateInvalidException extends RuntimeException {
    public StoredStateInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}

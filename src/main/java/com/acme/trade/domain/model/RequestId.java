package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

/** Caller-supplied idempotency key, scoped to this demo's order API. */
public record RequestId(String value) {
    public RequestId {
        if (value == null || value.isBlank() || value.length() > 128 || !value.equals(value.trim())) {
            throw new ValidationException("Idempotency-Key must contain 1..128 non-blank characters without surrounding whitespace");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new ValidationException("Idempotency-Key cannot contain whitespace or control characters");
            }
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

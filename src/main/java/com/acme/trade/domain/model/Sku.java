package com.acme.trade.domain.model;

import com.acme.trade.domain.exception.ValidationException;

import java.util.regex.Pattern;

/** Stock-keeping unit; deliberately independent of persistence and web validation. */
public record Sku(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public Sku {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new ValidationException("sku must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        }
    }

    public static Sku of(String value) {
        return new Sku(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

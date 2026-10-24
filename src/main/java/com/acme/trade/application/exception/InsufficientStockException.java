package com.acme.trade.application.exception;

import com.acme.trade.domain.model.Sku;

public final class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Sku sku) {
        super("insufficient stock or unknown sku: " + sku.value());
    }
}

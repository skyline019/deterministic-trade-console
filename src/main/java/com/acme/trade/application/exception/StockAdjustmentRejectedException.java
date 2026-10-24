package com.acme.trade.application.exception;

public final class StockAdjustmentRejectedException extends RuntimeException {
    public StockAdjustmentRejectedException(String message) {
        super(message);
    }
}

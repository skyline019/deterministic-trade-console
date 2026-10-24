package com.acme.trade.application.port;

import java.util.function.Supplier;

public interface TransactionRunner {
    <T> T inTransaction(TransactionMode mode, Supplier<T> action);
}

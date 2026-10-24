package com.acme.trade.application.port;

import com.acme.trade.domain.model.Inventory;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository {
    Optional<Inventory> find(Sku sku);

    List<Inventory> findAll();

    boolean create(Sku sku, StockLevel stock, Instant at);

    boolean adjust(Sku sku, int delta, Instant at);

    /** Atomic update of both available and reserved ledgers. */
    boolean tryReserve(Sku sku, Quantity quantity, Instant at);

    boolean releaseReservation(Sku sku, Quantity quantity, Instant at);

    boolean confirmReservation(Sku sku, Quantity quantity, Instant at);

    /** Test/bootstrap support; production mutations use create/adjust with command receipts. */
    void replaceStock(Sku sku, StockLevel stock, Instant at);
}

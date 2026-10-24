package com.acme.trade.application.service;

import com.acme.trade.application.port.DashboardRepository;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.result.DashboardStats;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;

import java.util.Objects;

public final class DashboardService {
    private final DashboardRepository dashboard;
    private final TransactionRunner transactions;

    public DashboardService(DashboardRepository dashboard, TransactionRunner transactions) {
        this.dashboard = Objects.requireNonNull(dashboard, "dashboard");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public DashboardStats load(Actor actor) {
        actor.require(Permission.INVENTORY_READ);
        actor.require(Permission.ORDER_READ);
        return transactions.inTransaction(TransactionMode.READ_ONLY, dashboard::load);
    }
}

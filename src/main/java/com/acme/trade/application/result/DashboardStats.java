package com.acme.trade.application.result;

public record DashboardStats(
        long skuCount,
        long availableStock,
        long reservedStock,
        long totalOrders,
        long reservedOrders,
        long confirmedOrders,
        long cancelledOrders) {
}

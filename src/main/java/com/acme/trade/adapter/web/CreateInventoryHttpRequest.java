package com.acme.trade.adapter.web;

public record CreateInventoryHttpRequest(String sku, Integer initialStock, String reason) {
}

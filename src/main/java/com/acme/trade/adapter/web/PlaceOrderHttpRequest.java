package com.acme.trade.adapter.web;

/** JSON transport shape; domain value objects perform validation after mapping. */
public record PlaceOrderHttpRequest(String sku, Integer quantity) {
}

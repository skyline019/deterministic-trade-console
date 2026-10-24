package com.acme.trade.adapter.web;

public record AdjustInventoryHttpRequest(Integer delta, String reason) {
}

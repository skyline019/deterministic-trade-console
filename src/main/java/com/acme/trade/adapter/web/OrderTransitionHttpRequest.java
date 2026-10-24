package com.acme.trade.adapter.web;

/**
 * Transition body. For confirmation, {@code quantity} is optional: null = confirm all remaining reserved units.
 */
public record OrderTransitionHttpRequest(String reason, Integer quantity) {
    public OrderTransitionHttpRequest(String reason) {
        this(reason, null);
    }
}

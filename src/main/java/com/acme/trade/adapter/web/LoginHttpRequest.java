package com.acme.trade.adapter.web;

/** Login body. {@code delivery} is {@code bearer} (default) or {@code cookie}. */
public record LoginHttpRequest(String username, String password, String delivery) {
    public LoginHttpRequest {
        if (delivery == null || delivery.isBlank()) {
            delivery = "bearer";
        }
    }

    /** Jackson / tests without delivery field. */
    public LoginHttpRequest(String username, String password) {
        this(username, password, "bearer");
    }

    @Override
    public String toString() {
        return "LoginHttpRequest[credentials=REDACTED,delivery=" + delivery + "]";
    }
}

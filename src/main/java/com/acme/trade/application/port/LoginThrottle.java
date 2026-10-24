package com.acme.trade.application.port;

public interface LoginThrottle {
    void check(String fingerprint);

    void reset(String fingerprint);
}

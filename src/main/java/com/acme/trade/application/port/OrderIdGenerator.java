package com.acme.trade.application.port;

import com.acme.trade.domain.model.OrderId;

public interface OrderIdGenerator {
    OrderId newId();
}

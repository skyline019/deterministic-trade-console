package com.acme.trade.adapter.id;

import com.acme.trade.application.port.OrderIdGenerator;
import com.acme.trade.domain.model.OrderId;

public final class UuidOrderIdGenerator implements OrderIdGenerator {
    @Override
    public OrderId newId() {
        return OrderId.random();
    }
}

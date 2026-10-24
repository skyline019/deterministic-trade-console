package com.acme.trade.adapter.id;

import com.acme.trade.application.port.AuditIdGenerator;
import com.acme.trade.application.port.UserIdGenerator;

import java.util.UUID;

public final class UuidValueGenerator implements AuditIdGenerator, UserIdGenerator {
    @Override
    public UUID newId() {
        return UUID.randomUUID();
    }
}

package com.acme.trade.application.port;

import java.util.UUID;

public interface AuditIdGenerator {
    UUID newId();
}

package com.acme.trade.application.port;

import java.util.UUID;

public interface UserIdGenerator {
    UUID newId();
}

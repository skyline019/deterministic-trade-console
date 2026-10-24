package com.acme.trade.application.port;

import java.time.Duration;
import java.util.Optional;

public interface LockManager {
    Optional<LockHandle> tryAcquire(String key, Duration ttl);
}

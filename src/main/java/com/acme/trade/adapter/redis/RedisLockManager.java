package com.acme.trade.adapter.redis;

import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.port.LockHandle;
import com.acme.trade.application.port.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** TTL mutex with owner-checked release. Stock correctness does not rely on this lock. */
public final class RedisLockManager implements LockManager {
    private static final Logger log = LoggerFactory.getLogger(RedisLockManager.class);

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> acquireScript;
    private final DefaultRedisScript<Long> releaseScript;

    public RedisLockManager(StringRedisTemplate redis, Resource acquireScript, Resource releaseScript) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.acquireScript = script(acquireScript);
        this.releaseScript = script(releaseScript);
    }

    private static DefaultRedisScript<Long> script(Resource resource) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(Objects.requireNonNull(resource, "script resource"));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("lock ttl must be positive");
        }
        String token = UUID.randomUUID().toString();
        try {
            Long result = redis.execute(acquireScript, List.of(key), token, Long.toString(ttl.toMillis()));
            if (result == null) {
                throw new LockUnavailableException("Redis returned no lock-acquisition result");
            }
            if (result == 1L) {
                return Optional.of(new RedisLockHandle(key, token));
            }
            return Optional.empty();
        } catch (LockUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LockUnavailableException("Redis lock acquisition failed", exception);
        }
    }

    private final class RedisLockHandle implements LockHandle {
        private final String key;
        private final String token;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RedisLockHandle(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                redis.execute(releaseScript, List.of(key), token);
            } catch (RuntimeException exception) {
                // Do not mask a committed business result. The TTL removes the abandoned lock.
                log.warn("Could not release Redis lock {}; its TTL will expire it", key, exception);
            }
        }
    }
}

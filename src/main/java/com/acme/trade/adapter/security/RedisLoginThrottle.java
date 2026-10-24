package com.acme.trade.adapter.security;

import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.exception.RateLimitExceededException;
import com.acme.trade.application.port.LoginThrottle;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class RedisLoginThrottle implements LoginThrottle {
    private static final String PREFIX = "trade:login-throttle:";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final int limit;
    private final Duration window;

    public RedisLoginThrottle(StringRedisTemplate redis, Resource script, int limit, Duration window) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(Objects.requireNonNull(script, "script"));
        this.script.setResultType(Long.class);
        if (limit < 1 || window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("invalid login throttle settings");
        }
        this.limit = limit;
        this.window = window;
    }

    @Override
    public void check(String fingerprint) {
        try {
            Long allowed = redis.execute(script, List.of(PREFIX + fingerprint),
                    Integer.toString(limit), Long.toString(window.toMillis()));
            if (allowed == null) {
                throw new LockUnavailableException("login throttle returned no result");
            }
            if (allowed <= 0L) {
                throw new RateLimitExceededException(allowed < 0 ? -allowed : 0);
            }
        } catch (RateLimitExceededException | LockUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LockUnavailableException("login throttle unavailable", exception);
        }
    }

    @Override
    public void reset(String fingerprint) {
        try {
            redis.delete(PREFIX + fingerprint);
        } catch (RuntimeException exception) {
            throw new LockUnavailableException("login throttle reset failed", exception);
        }
    }
}

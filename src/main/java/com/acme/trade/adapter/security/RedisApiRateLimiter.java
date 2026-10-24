package com.acme.trade.adapter.security;

import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.exception.RateLimitExceededException;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Fixed-window API rate limit keyed by authenticated actor (fail-closed on Redis errors). */
public final class RedisApiRateLimiter {
    private static final String PREFIX = "trade:api-throttle:";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final int limit;
    private final Duration window;

    public RedisApiRateLimiter(StringRedisTemplate redis, Resource script, int limit, Duration window) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(Objects.requireNonNull(script, "script"));
        this.script.setResultType(Long.class);
        if (limit < 1 || window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("invalid api rate-limit settings");
        }
        this.limit = limit;
        this.window = window;
    }

    public void check(String actorId) {
        Objects.requireNonNull(actorId, "actorId");
        try {
            Long allowed = redis.execute(script, List.of(PREFIX + actorId),
                    Integer.toString(limit), Long.toString(window.toMillis()));
            if (allowed == null) {
                throw new LockUnavailableException("api rate limit returned no result");
            }
            if (allowed <= 0L) {
                throw new RateLimitExceededException("API_RATE_LIMITED",
                        "too many API requests; retry later",
                        allowed < 0 ? -allowed : 0);
            }
        } catch (RateLimitExceededException | LockUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LockUnavailableException("api rate limit unavailable", exception);
        }
    }
}

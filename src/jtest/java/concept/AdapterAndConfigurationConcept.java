package concept;

import com.acme.trade.adapter.redis.RedisLockManager;
import com.acme.trade.adapter.redis.RetryingLockManager;
import com.acme.trade.adapter.security.RedisApiRateLimiter;
import com.acme.trade.application.port.LockHandle;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.adapter.security.BCryptPasswordCodec;
import com.acme.trade.adapter.security.RedisLoginThrottle;
import com.acme.trade.adapter.security.SecureAccessTokenGenerator;
import com.acme.trade.adapter.transaction.SpringTransactionRunner;
import com.acme.trade.config.SecuritySettings;
import com.acme.trade.config.TradeSettings;
import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.exception.RateLimitExceededException;
import com.acme.trade.domain.security.AccessTokenHash;
import jtest.Concept;
import jtest.JTestRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectFalse;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class AdapterAndConfigurationConcept implements Concept {
    @Override
    public String name() {
        return "AdapterBoundaries";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("secure_tokens_are_bounded_urlsafe_and_unique_across_a_batch", () -> {
            SecureAccessTokenGenerator generator = new SecureAccessTokenGenerator(new SecureRandom());
            Set<String> tokens = new HashSet<>();
            for (int index = 0; index < 512; index++) {
                String token = generator.newToken();
                expectTrue(AccessTokenHash.isValidRawToken(token));
                expectEquals(43, token.length());
                expectTrue(tokens.add(token));
            }
            expectThrows(NullPointerException.class, () -> new SecureAccessTokenGenerator(null));
        });

        tests.test("bcrypt_verifies_only_the_right_password_and_uses_per_password_salts", () -> {
            BCryptPasswordCodec codec = new BCryptPasswordCodec(10);
            String first = codec.hash("correct-password");
            String second = codec.hash("correct-password");
            expectTrue(codec.matches("correct-password", first));
            expectTrue(codec.matches("correct-password", second));
            expectFalse(codec.matches("wrong-password", first));
            expectFalse(first.equals(second));
            expectEquals(60, first.length());
            expectThrows(NullPointerException.class, () -> codec.matches(null, first));
            expectThrows(IllegalArgumentException.class, () -> new BCryptPasswordCodec(9));
            expectThrows(IllegalArgumentException.class, () -> new BCryptPasswordCodec(15));
        });

        tests.test("trade_and_transaction_timeouts_fail_fast_at_invalid_edges", () -> {
            Duration backoff = Duration.ofMillis(50);
            Duration reservation = Duration.ofHours(1);
            new TradeSettings(Duration.ofSeconds(10), 5, 3, backoff, reservation);
            expectThrows(IllegalArgumentException.class,
                    () -> new TradeSettings(Duration.ZERO, 5, 3, backoff, reservation));
            expectThrows(IllegalArgumentException.class,
                    () -> new TradeSettings(Duration.ofSeconds(-1), 5, 3, backoff, reservation));
            expectThrows(IllegalArgumentException.class,
                    () -> new TradeSettings(Duration.ofSeconds(5), 5, 3, backoff, reservation));
            expectThrows(IllegalArgumentException.class, () -> new TradeSettings(
                    Duration.ofSeconds(4), 5, 3, backoff, reservation));
            expectThrows(IllegalArgumentException.class, () -> new TradeSettings(
                    Duration.ofSeconds(10), 0, 3, backoff, reservation));
            expectThrows(IllegalArgumentException.class, () -> new TradeSettings(
                    null, 5, 3, backoff, reservation));
            expectThrows(IllegalArgumentException.class, () -> new TradeSettings(
                    Duration.ofSeconds(10), 5, 0, backoff, reservation));
            expectThrows(IllegalArgumentException.class, () -> new TradeSettings(
                    Duration.ofSeconds(10), 5, 3, backoff, Duration.ofSeconds(30)));
            new SpringTransactionRunner(new DataSourceTransactionManager(), 1);
            expectThrows(IllegalArgumentException.class,
                    () -> new SpringTransactionRunner(new DataSourceTransactionManager(), 0));
            expectThrows(NullPointerException.class, () -> new SpringTransactionRunner(null, 1));
        });

        tests.test("security_and_redis_configuration_rejects_zero_negative_and_oversized_values", () -> {
            validSecuritySettings();
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ZERO, 12, 10, Duration.ofMinutes(5), "admin", 300, Duration.ofMinutes(1), false, false, "hs256", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(25), 12, 10, Duration.ofMinutes(5), "admin", 300, Duration.ofMinutes(1), false, false, "hs256", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 9, 10, Duration.ofMinutes(5), "admin", 300, Duration.ofMinutes(1), false, false, "hs256", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 15, 10, Duration.ofMinutes(5), "admin", 300, Duration.ofMinutes(1), false, false, "hs256", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 12, 0, Duration.ofMinutes(5), "admin", 300, Duration.ofMinutes(1), false, false, "hs256", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 12, 10, Duration.ZERO, "admin", 300, Duration.ofMinutes(1), false, false, "hs256", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "not valid", 300, Duration.ofMinutes(1), false, false, "hs256", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin", 0, Duration.ofMinutes(1), false, false, "hs256", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin", 300, Duration.ofMinutes(1), false, true, "hs256", "short", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin", 300, Duration.ofMinutes(1), false, true, "jwks", "", "", true));
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin", 300, Duration.ofMinutes(1), false, false, "hs256", "", "", false));

            StringRedisTemplate redis = new StringRedisTemplate();
            RedisLockManager locks = new RedisLockManager(redis, new ClassPathResource("redis/acquire-lock.lua"),
                    new ClassPathResource("redis/release-lock.lua"));
            expectThrows(IllegalArgumentException.class, () -> locks.tryAcquire("key", Duration.ZERO));
            expectThrows(IllegalArgumentException.class, () -> locks.tryAcquire("key", Duration.ofMillis(-1)));
            expectThrows(IllegalArgumentException.class, () -> new RedisLoginThrottle(redis,
                    new ClassPathResource("redis/login-throttle.lua"), 0, Duration.ofMinutes(1)));
            expectThrows(IllegalArgumentException.class, () -> new RedisLoginThrottle(redis,
                    new ClassPathResource("redis/login-throttle.lua"), 10, Duration.ZERO));
        });

        tests.test("login_throttle_preserves_remaining_retry_time_and_fails_closed_on_null_script_result", () -> {
            expectEquals(60, new RateLimitExceededException().retryAfterSeconds());
            expectEquals(1, new RateLimitExceededException(1).retryAfterSeconds());
            expectEquals(1, new RateLimitExceededException(1000).retryAfterSeconds());
            expectEquals(2, new RateLimitExceededException(1001).retryAfterSeconds());
            expectEquals(Long.MAX_VALUE / 1000 + 1,
                    new RateLimitExceededException(Long.MAX_VALUE).retryAfterSeconds());

            RedisLoginThrottle limited = new RedisLoginThrottle(redisResult(-1501L),
                    new ClassPathResource("redis/login-throttle.lua"), 3, Duration.ofMinutes(5));
            RateLimitExceededException exception = expectThrows(RateLimitExceededException.class,
                    () -> limited.check("bucket"));
            expectEquals(2, exception.retryAfterSeconds());

            RedisLoginThrottle unavailable = new RedisLoginThrottle(redisResult(null),
                    new ClassPathResource("redis/login-throttle.lua"), 3, Duration.ofMinutes(5));
            expectThrows(LockUnavailableException.class, () -> unavailable.check("bucket"));

            RedisApiRateLimiter apiLimited = new RedisApiRateLimiter(redisResult(-2500L),
                    new ClassPathResource("redis/login-throttle.lua"), 100, Duration.ofMinutes(1));
            RateLimitExceededException apiLimit = expectThrows(RateLimitExceededException.class,
                    () -> apiLimited.check("actor-1"));
            expectEquals("API_RATE_LIMITED", apiLimit.code());
            expectEquals(3, apiLimit.retryAfterSeconds());
        });

        tests.test("retrying_lock_manager_retries_io_failures_but_not_contention", () -> {
            CountingLockManager contended = new CountingLockManager(0, Optional.empty());
            expectTrue(new RetryingLockManager(contended, 3, Duration.ZERO)
                    .tryAcquire("k", Duration.ofSeconds(1)).isEmpty());
            expectEquals(1, contended.calls);

            CountingLockManager recovering = new CountingLockManager(2, Optional.of(new NoopLock()));
            expectTrue(new RetryingLockManager(recovering, 3, Duration.ZERO)
                    .tryAcquire("k", Duration.ofSeconds(1)).isPresent());
            expectEquals(3, recovering.calls);

            CountingLockManager alwaysDown = new CountingLockManager(99, Optional.of(new NoopLock()));
            expectThrows(LockUnavailableException.class,
                    () -> new RetryingLockManager(alwaysDown, 2, Duration.ZERO)
                            .tryAcquire("k", Duration.ofSeconds(1)));
            expectEquals(2, alwaysDown.calls);
        });
    }

    private static final class CountingLockManager implements LockManager {
        private final int failTimes;
        private final Optional<LockHandle> success;
        private int calls;

        private CountingLockManager(int failTimes, Optional<LockHandle> success) {
            this.failTimes = failTimes;
            this.success = success;
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            calls++;
            if (calls <= failTimes) {
                throw new LockUnavailableException("injected");
            }
            return success;
        }
    }

    private static final class NoopLock implements LockHandle {
        @Override
        public void close() {
        }
    }

    private static StringRedisTemplate redisResult(Long result) {
        return new StringRedisTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                return (T) result;
            }
        };
    }

    private static void validSecuritySettings() {
        new SecuritySettings(Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin",
                300, Duration.ofMinutes(1), false, false, "hs256", "", "", true);
        new SecuritySettings(Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin",
                300, Duration.ofMinutes(1), false, true, "hs256", "01234567890123456789012345678901", "", true);
        new SecuritySettings(Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin",
                300, Duration.ofMinutes(1), false, true, "jwks", "",
                "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"t\",\"n\":\"AQAB\",\"e\":\"AQAB\"}]}", true);
    }
}

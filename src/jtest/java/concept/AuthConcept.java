package concept;

import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.port.AccessTokenGenerator;
import com.acme.trade.application.port.ApiTokenRepository;
import com.acme.trade.application.port.LoginThrottle;
import com.acme.trade.application.port.PasswordCodec;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.port.UserRepository;
import com.acme.trade.application.service.AuthService;
import com.acme.trade.domain.security.AccessTokenHash;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;
import com.acme.trade.domain.security.UserAccount;
import jtest.Concept;
import jtest.JTestRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static concept.ConceptFixture.NOW;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class AuthConcept implements Concept {
    private static final String RAW_TOKEN = "A".repeat(43);

    @Override
    public String name() {
        return "Authentication";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("only_token_hash_is_persisted_and_revocation_is_immediate", () -> {
            FakeAuthWorld world = new FakeAuthWorld();

            var issued = world.service.issue("ADMIN", "correct-password", "127.0.0.1");

            expectEquals(RAW_TOKEN, issued.rawToken());
            expectEquals(NOW.plus(Duration.ofHours(1)), issued.expiresAt());
            expectEquals(AccessTokenHash.of(RAW_TOKEN), world.tokens.values.keySet().iterator().next());
            expectTrue(!world.tokens.values.containsKey(RAW_TOKEN));
            expectEquals("admin", world.service.authenticate(RAW_TOKEN).username());
            expectEquals(1, world.throttle.resets);

            world.service.revoke(RAW_TOKEN);
            expectTrue(world.tokens.values.isEmpty());
            expectThrows(AuthenticationException.class, () -> world.service.authenticate(RAW_TOKEN));
        });

        tests.test("bad_password_is_generic_and_issues_no_token", () -> {
            FakeAuthWorld world = new FakeAuthWorld();

            expectThrows(AuthenticationException.class,
                    () -> world.service.issue("admin", "wrong-password", "127.0.0.1"));

            expectTrue(world.tokens.values.isEmpty());
            expectEquals(0, world.throttle.resets);
            expectEquals("hash:correct-password", world.passwords.lastHash);
        });

        tests.test("unknown_user_uses_dummy_hash_and_disabled_user_cannot_login", () -> {
            FakeAuthWorld unknown = new FakeAuthWorld();
            expectThrows(AuthenticationException.class,
                    () -> unknown.service.issue("missing", "correct-password", "127.0.0.1"));
            expectEquals("dummy-hash", unknown.passwords.lastHash);
            expectTrue(unknown.tokens.values.isEmpty());

            FakeAuthWorld disabled = new FakeAuthWorld();
            disabled.addUser("disabled", false);
            expectThrows(AuthenticationException.class,
                    () -> disabled.service.issue("disabled", "correct-password", "127.0.0.1"));
            expectEquals("hash:correct-password", disabled.passwords.lastHash);
            expectTrue(disabled.tokens.values.isEmpty());
        });

        tests.test("expired_and_malformed_tokens_fail_before_or_at_repository_boundary", () -> {
            FakeAuthWorld world = new FakeAuthWorld();
            world.service.issue("admin", "correct-password", "127.0.0.1");
            world.clock.advance(Duration.ofHours(2));
            expectThrows(AuthenticationException.class, () -> world.service.authenticate(RAW_TOKEN));

            FakeAuthWorld malformed = new FakeAuthWorld();
            for (String token : new String[]{null, "", "short", "A".repeat(42), "A".repeat(44), "+".repeat(43)}) {
                expectThrows(AuthenticationException.class, () -> malformed.service.authenticate(token));
                expectThrows(AuthenticationException.class, () -> malformed.service.revoke(token));
            }
            expectEquals(0, malformed.tokens.lookups);
            expectEquals(0, malformed.transactions.writes);
        });

        tests.test("throttle_failure_stops_before_bcrypt_or_token_persistence", () -> {
            FakeAuthWorld world = new FakeAuthWorld();
            world.throttle.checkFailure = new LockUnavailableException("redis down");

            expectThrows(LockUnavailableException.class,
                    () -> world.service.issue("admin", "correct-password", "127.0.0.1"));

            expectEquals(0, world.passwords.calls);
            expectEquals(0, world.transactions.writes);
            expectTrue(world.tokens.values.isEmpty());
        });

        tests.test("throttle_reset_failure_cannot_leave_an_unreturned_valid_token", () -> {
            FakeAuthWorld world = new FakeAuthWorld();
            world.throttle.resetFailure = new LockUnavailableException("reset down");

            expectThrows(LockUnavailableException.class,
                    () -> world.service.issue("admin", "correct-password", "127.0.0.1"));

            expectTrue(world.tokens.values.isEmpty());
            expectEquals(0, world.transactions.writes);
            expectEquals(1, world.throttle.resets);
        });

        tests.test("null_and_invalid_login_shapes_use_the_same_public_failure", () -> {
            FakeAuthWorld world = new FakeAuthWorld();
            for (String username : new String[]{null, "", " ", "ab", "x".repeat(33)}) {
                expectThrows(AuthenticationException.class,
                        () -> world.service.issue(username, "correct-password", "unknown"));
            }
            for (String password : new String[]{null, "", "密".repeat(25)}) {
                expectThrows(AuthenticationException.class,
                        () -> world.service.issue("admin", password, "unknown"));
            }
            expectTrue(world.tokens.values.isEmpty());
            expectEquals(0, world.throttle.resets);
        });
    }

    private static final class FakeAuthWorld {
        final FakeUsers users = new FakeUsers();
        final FakeTokens tokens = new FakeTokens();
        final FakeThrottle throttle = new FakeThrottle();
        final FakePasswords passwords = new FakePasswords();
        final FakeTransactions transactions = new FakeTransactions();
        final MutableClock clock = new MutableClock(NOW);
        final AuthService service;

        FakeAuthWorld() {
            users.insert(new UserAccount(new UUID(0, 1), "admin", "hash:correct-password", true, Set.of(Role.ADMIN)));
            AccessTokenGenerator generator = () -> RAW_TOKEN;
            service = new AuthService(users, tokens, passwords, generator, throttle, transactions,
                    clock, Duration.ofHours(1), "dummy-hash");
        }

        void addUser(String username, boolean enabled) {
            users.insert(new UserAccount(new UUID(0, users.values.size() + 1L), username,
                    "hash:correct-password", enabled, Set.of(Role.VIEWER)));
        }
    }

    private static final class FakeUsers implements UserRepository {
        final Map<String, UserAccount> values = new HashMap<>();

        @Override
        public Optional<UserAccount> findByUsername(String username) {
            return Optional.ofNullable(values.get(username));
        }

        @Override
        public long count() {
            return values.size();
        }

        @Override
        public void insert(UserAccount user) {
            values.put(user.username(), user);
        }
    }

    private static final class FakePasswords implements PasswordCodec {
        int calls;
        String lastHash;

        @Override
        public String hash(String rawPassword) {
            return "hash:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String encodedHash) {
            calls++;
            lastHash = encodedHash;
            return encodedHash.equals(hash(rawPassword));
        }
    }

    private static final class FakeThrottle implements LoginThrottle {
        int checks;
        int resets;
        RuntimeException checkFailure;
        RuntimeException resetFailure;

        @Override
        public void check(String fingerprint) {
            checks++;
            if (checkFailure != null) {
                throw checkFailure;
            }
        }

        @Override
        public void reset(String fingerprint) {
            resets++;
            if (resetFailure != null) {
                throw resetFailure;
            }
        }
    }

    private static final class FakeTransactions implements TransactionRunner {
        int writes;

        @Override
        public <T> T inTransaction(TransactionMode mode, Supplier<T> action) {
            if (mode == TransactionMode.WRITE) {
                writes++;
            }
            return action.get();
        }
    }

    private static final class FakeTokens implements ApiTokenRepository {
        final Map<String, TokenRecord> values = new HashMap<>();
        int lookups;

        @Override
        public void insert(String tokenHash, UserAccount user, Instant createdAt, Instant expiresAt) {
            values.put(tokenHash, new TokenRecord(user.actor(), expiresAt));
        }

        @Override
        public Optional<Actor> findActiveActor(String tokenHash, Instant now) {
            lookups++;
            TokenRecord token = values.get(tokenHash);
            return token != null && token.expiresAt.isAfter(now) ? Optional.of(token.actor) : Optional.empty();
        }

        @Override
        public boolean delete(String tokenHash) {
            return values.remove(tokenHash) != null;
        }
    }

    private record TokenRecord(Actor actor, Instant expiresAt) {
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

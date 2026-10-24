package concept;

import com.acme.trade.adapter.security.JwksJwtAccessTokenAuthenticator;
import com.acme.trade.adapter.web.AuthHandler;
import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.application.port.AccessTokenGenerator;
import com.acme.trade.application.port.ApiTokenRepository;
import com.acme.trade.application.port.LoginThrottle;
import com.acme.trade.application.port.PasswordCodec;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.port.UserRepository;
import com.acme.trade.application.service.AuthService;
import com.acme.trade.config.SecuritySettings;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;
import com.acme.trade.domain.security.Role;
import com.acme.trade.domain.security.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import jtest.Concept;
import jtest.JTestRegistry;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;

import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

/** L4: static JWKS RS256 AuthN with kid rotation; password login can be disabled. */
public final class JwksAuthConcept implements Concept {
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return "JwksAuthn";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("rs256_token_with_active_kid_maps_to_actor", () -> {
            KeyPair pair = JwksJwtAccessTokenAuthenticator.generateRsaKeyPair();
            String jwks = JwksJwtAccessTokenAuthenticator.toJwksJson(JSON, Map.of("kid-a", pair.getPublic()));
            JwksJwtAccessTokenAuthenticator jwt = new JwksJwtAccessTokenAuthenticator(jwks, JSON, CLOCK);
            String token = JwksJwtAccessTokenAuthenticator.mint(pair.getPrivate(), "kid-a", "user-rs", "operator",
                    EnumSet.of(Role.OPERATOR), NOW.getEpochSecond() + 3600, JSON);
            Actor actor = jwt.authenticate(token);
            expectEquals("user-rs", actor.id());
            expectTrue(actor.has(Permission.ORDER_WRITE));
        });

        tests.test("kid_rotation_rejects_retired_key_and_accepts_new_kid", () -> {
            KeyPair retired = JwksJwtAccessTokenAuthenticator.generateRsaKeyPair();
            KeyPair active = JwksJwtAccessTokenAuthenticator.generateRsaKeyPair();
            String tokenOld = JwksJwtAccessTokenAuthenticator.mint(retired.getPrivate(), "kid-old", "u1", "operator",
                    EnumSet.of(Role.OPERATOR), NOW.getEpochSecond() + 3600, JSON);
            String tokenNew = JwksJwtAccessTokenAuthenticator.mint(active.getPrivate(), "kid-new", "u2", "admin",
                    EnumSet.of(Role.ADMIN), NOW.getEpochSecond() + 3600, JSON);

            Map<String, PublicKey> rotated = new LinkedHashMap<>();
            rotated.put("kid-new", active.getPublic());
            String jwks = JwksJwtAccessTokenAuthenticator.toJwksJson(JSON, rotated);
            JwksJwtAccessTokenAuthenticator jwt = new JwksJwtAccessTokenAuthenticator(jwks, JSON, CLOCK);

            expectThrows(AuthenticationException.class, () -> jwt.authenticate(tokenOld));
            expectEquals("u2", jwt.authenticate(tokenNew).id());
        });

        tests.test("password_login_disabled_requires_jwt_and_forbids_issue", () -> {
            expectThrows(IllegalArgumentException.class, () -> new SecuritySettings(
                    Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin",
                    300, Duration.ofMinutes(1), false, false, "hs256", "", "", false));

            KeyPair pair = JwksJwtAccessTokenAuthenticator.generateRsaKeyPair();
            String jwks = JwksJwtAccessTokenAuthenticator.toJwksJson(JSON, Map.of("kid-a", pair.getPublic()));
            new SecuritySettings(Duration.ofHours(8), 12, 10, Duration.ofMinutes(5), "admin",
                    300, Duration.ofMinutes(1), false, true, "jwks", "", jwks, false);

            AuthService auth = stubAuth();
            AuthHandler handler = new AuthHandler(auth, false, false);
            MockHttpServletRequest servlet = new MockHttpServletRequest("POST", "/api/v1/auth/token");
            servlet.setContentType("application/json");
            servlet.setContent("{\"username\":\"admin\",\"password\":\"anything\"}".getBytes());
            ServerRequest request = ServerRequest.create(servlet, List.of());
            expectThrows(AccessDeniedException.class, () -> handler.issue(request));
        });
    }

    private static AuthService stubAuth() {
        UserRepository users = new UserRepository() {
            @Override
            public Optional<UserAccount> findByUsername(String username) {
                return Optional.empty();
            }

            @Override
            public long count() {
                return 0;
            }

            @Override
            public void insert(UserAccount user) {
            }
        };
        ApiTokenRepository tokens = new ApiTokenRepository() {
            @Override
            public void insert(String tokenHash, UserAccount user, Instant createdAt, Instant expiresAt) {
            }

            @Override
            public Optional<Actor> findActiveActor(String tokenHash, Instant now) {
                return Optional.empty();
            }

            @Override
            public boolean delete(String tokenHash) {
                return false;
            }
        };
        PasswordCodec passwords = new PasswordCodec() {
            @Override
            public String hash(String rawPassword) {
                return "h";
            }

            @Override
            public boolean matches(String rawPassword, String encodedHash) {
                return false;
            }
        };
        AccessTokenGenerator generator = () -> "token";
        LoginThrottle throttle = new LoginThrottle() {
            @Override
            public void check(String fingerprint) {
            }

            @Override
            public void reset(String fingerprint) {
            }
        };
        TransactionRunner transactions = new TransactionRunner() {
            @Override
            public <T> T inTransaction(TransactionMode mode, Supplier<T> action) {
                return action.get();
            }
        };
        return new AuthService(users, tokens, passwords, generator, throttle, transactions,
                CLOCK, Duration.ofHours(1), "dummy");
    }
}

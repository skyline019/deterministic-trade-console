package concept;

import com.acme.trade.adapter.security.CompositeAccessTokenAuthenticator;
import com.acme.trade.adapter.security.Hs256JwtAccessTokenAuthenticator;
import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.application.port.AccessTokenAuthenticator;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;
import com.acme.trade.domain.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jtest.Concept;
import jtest.JTestRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

/** Optional JWT AuthN port: HS256 → Actor; opaque path unchanged when token is not JWT-shaped. */
public final class JwtAuthConcept implements Concept {
    private static final String SECRET = "01234567890123456789012345678901";
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return "JwtAuthn";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("valid_hs256_jwt_maps_to_actor_with_roles", () -> {
            Hs256JwtAccessTokenAuthenticator jwt = new Hs256JwtAccessTokenAuthenticator(SECRET, JSON, CLOCK);
            String token = Hs256JwtAccessTokenAuthenticator.mint(SECRET, "user-1", "operator",
                    EnumSet.of(Role.OPERATOR), NOW.getEpochSecond() + 3600, JSON);
            Actor actor = jwt.authenticate(token);
            expectEquals("user-1", actor.id());
            expectEquals("operator", actor.username());
            expectTrue(actor.has(Permission.ORDER_WRITE));
        });

        tests.test("expired_or_bad_signature_jwt_is_rejected", () -> {
            Hs256JwtAccessTokenAuthenticator jwt = new Hs256JwtAccessTokenAuthenticator(SECRET, JSON, CLOCK);
            String expired = Hs256JwtAccessTokenAuthenticator.mint(SECRET, "user-1", "operator",
                    EnumSet.of(Role.OPERATOR), NOW.getEpochSecond() - 10, JSON);
            expectThrows(AuthenticationException.class, () -> jwt.authenticate(expired));

            String good = Hs256JwtAccessTokenAuthenticator.mint(SECRET, "user-1", "operator",
                    EnumSet.of(Role.OPERATOR), NOW.getEpochSecond() + 3600, JSON);
            String tampered = good.substring(0, good.length() - 4) + "xxxx";
            expectThrows(AuthenticationException.class, () -> jwt.authenticate(tampered));
        });

        tests.test("composite_routes_jwt_vs_opaque", () -> {
            AccessTokenAuthenticator opaque = raw -> {
                if ("opaque-token-value-with-enough-entropy-aaaa".equals(raw)) {
                    return new Actor("opaque-id", "admin", Set.of(Role.ADMIN));
                }
                throw new AuthenticationException();
            };
            AccessTokenAuthenticator composite = new CompositeAccessTokenAuthenticator(
                    opaque, Optional.of(new Hs256JwtAccessTokenAuthenticator(SECRET, JSON, CLOCK)));

            String jwt = Hs256JwtAccessTokenAuthenticator.mint(SECRET, "jwt-user", "auditor",
                    EnumSet.of(Role.AUDITOR), NOW.getEpochSecond() + 3600, JSON);
            expectEquals("jwt-user", composite.authenticate(jwt).id());
            expectEquals("admin", composite.authenticate("opaque-token-value-with-enough-entropy-aaaa").username());
        });
    }
}

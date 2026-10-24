package com.acme.trade.config;

import com.acme.trade.adapter.persistence.MySqlApiTokenRepository;
import com.acme.trade.adapter.persistence.MySqlUserRepository;
import com.acme.trade.adapter.observability.CorrelationIdFilter;
import com.acme.trade.adapter.security.AdminBootstrapRunner;
import com.acme.trade.adapter.security.ApiRateLimitFilter;
import com.acme.trade.adapter.security.BCryptPasswordCodec;
import com.acme.trade.adapter.security.BearerSecurityFilter;
import com.acme.trade.adapter.security.CompositeAccessTokenAuthenticator;
import com.acme.trade.adapter.security.Hs256JwtAccessTokenAuthenticator;
import com.acme.trade.adapter.security.JwksJwtAccessTokenAuthenticator;
import com.acme.trade.adapter.security.RedisApiRateLimiter;
import com.acme.trade.adapter.security.RedisLoginThrottle;
import com.acme.trade.adapter.security.SecureAccessTokenGenerator;
import com.acme.trade.application.port.AccessTokenAuthenticator;
import com.acme.trade.application.port.AccessTokenGenerator;
import com.acme.trade.application.port.ApiTokenRepository;
import com.acme.trade.application.port.LoginThrottle;
import com.acme.trade.application.port.PasswordCodec;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.port.UserIdGenerator;
import com.acme.trade.application.port.UserRepository;
import com.acme.trade.application.service.AdminBootstrapService;
import com.acme.trade.application.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecuritySettings securitySettings(Environment environment) {
        Binder binder = Binder.get(environment);
        return new SecuritySettings(
                binder.bind("trade.security.token-ttl", Duration.class).orElse(Duration.ofHours(8)),
                binder.bind("trade.security.bcrypt-strength", Integer.class).orElse(12),
                binder.bind("trade.security.login-attempt-limit", Integer.class).orElse(10),
                binder.bind("trade.security.login-window", Duration.class).orElse(Duration.ofMinutes(5)),
                binder.bind("trade.security.bootstrap-admin-username", String.class).orElse("admin"),
                binder.bind("trade.security.api-attempt-limit", Integer.class).orElse(300),
                binder.bind("trade.security.api-window", Duration.class).orElse(Duration.ofMinutes(1)),
                binder.bind("trade.security.cookie.secure", Boolean.class).orElse(false),
                binder.bind("trade.security.jwt.enabled", Boolean.class).orElse(false),
                binder.bind("trade.security.jwt.mode", String.class).orElse("hs256"),
                binder.bind("trade.security.jwt.hs256-secret", String.class).orElse(""),
                binder.bind("trade.security.jwt.jwks-json", String.class).orElse(""),
                binder.bind("trade.security.password-login.enabled", Boolean.class).orElse(true));
    }

    @Bean
    PasswordCodec passwordCodec(SecuritySettings settings) {
        return new BCryptPasswordCodec(settings.bcryptStrength());
    }

    @Bean
    AccessTokenGenerator accessTokenGenerator() {
        return new SecureAccessTokenGenerator(new SecureRandom());
    }

    @Bean
    UserRepository userRepository(JdbcTemplate jdbc) {
        return new MySqlUserRepository(jdbc);
    }

    @Bean
    ApiTokenRepository apiTokenRepository(JdbcTemplate jdbc) {
        return new MySqlApiTokenRepository(jdbc);
    }

    @Bean
    LoginThrottle loginThrottle(StringRedisTemplate redis, SecuritySettings settings) {
        return new RedisLoginThrottle(redis, new ClassPathResource("redis/login-throttle.lua"),
                settings.loginAttemptLimit(), settings.loginWindow());
    }

    @Bean
    AuthService authService(UserRepository users, ApiTokenRepository tokens, PasswordCodec passwords,
                            AccessTokenGenerator tokenGenerator, LoginThrottle throttle,
                            TransactionRunner transactions, Clock clock, SecuritySettings settings) {
        // Work factor is also applied for unknown users, without revealing which path was taken.
        String dummyHash = passwords.hash("not-a-real-user-password");
        return new AuthService(users, tokens, passwords, tokenGenerator, throttle, transactions,
                clock, settings.tokenTtl(), dummyHash);
    }

    @Bean
    AdminBootstrapService adminBootstrapService(UserRepository users, PasswordCodec passwords,
                                                 UserIdGenerator ids, TransactionRunner transactions) {
        return new AdminBootstrapService(users, passwords, ids, transactions);
    }

    @Bean
    AdminBootstrapRunner adminBootstrapRunner(AdminBootstrapService service, Environment environment,
                                               SecuritySettings settings) {
        String password = Binder.get(environment).bind("trade.security.bootstrap-admin-password", String.class).orElse("");
        return new AdminBootstrapRunner(service, settings.bootstrapAdminUsername(), password);
    }

    @Bean
    RedisApiRateLimiter redisApiRateLimiter(StringRedisTemplate redis, SecuritySettings settings) {
        return new RedisApiRateLimiter(redis, new ClassPathResource("redis/login-throttle.lua"),
                settings.apiAttemptLimit(), settings.apiWindow());
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(
                new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        return registration;
    }

    /**
     * Primary for {@link AccessTokenAuthenticator}: AuthService is also that type (opaque path),
     * but HTTP filters must use the composite (opaque + optional JWT).
     */
    @Bean
    @Primary
    AccessTokenAuthenticator accessTokenAuthenticator(AuthService auth, SecuritySettings settings,
                                                      ObjectMapper json, Clock clock) {
        Optional<AccessTokenAuthenticator> jwt = Optional.empty();
        if (settings.jwtEnabled()) {
            if ("jwks".equals(settings.jwtMode())) {
                jwt = Optional.of(new JwksJwtAccessTokenAuthenticator(settings.jwtJwksJson(), json, clock));
            } else {
                jwt = Optional.of(new Hs256JwtAccessTokenAuthenticator(settings.jwtHs256Secret(), json, clock));
            }
        }
        return new CompositeAccessTokenAuthenticator(auth, jwt);
    }

    @Bean
    FilterRegistrationBean<BearerSecurityFilter> bearerSecurityFilter(AccessTokenAuthenticator authenticator,
                                                                      ObjectMapper json,
                                                                      SecuritySettings settings) {
        FilterRegistrationBean<BearerSecurityFilter> registration = new FilterRegistrationBean<>(
                new BearerSecurityFilter(authenticator, json, settings.cookieSecure()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiRateLimitFilter> apiRateLimitFilter(RedisApiRateLimiter limiter, ObjectMapper json) {
        FilterRegistrationBean<ApiRateLimitFilter> registration = new FilterRegistrationBean<>(
                new ApiRateLimitFilter(limiter, json));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        registration.setAsyncSupported(true);
        return registration;
    }
}

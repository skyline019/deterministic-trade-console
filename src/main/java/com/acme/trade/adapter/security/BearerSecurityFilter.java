package com.acme.trade.adapter.security;

import com.acme.trade.adapter.security.cookie.CookieAuthSupport;
import com.acme.trade.adapter.web.ActorRequest;
import com.acme.trade.adapter.web.ErrorResponse;
import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.application.port.AccessTokenAuthenticator;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit authentication boundary. Default: Bearer header.
 * Optional cookie delivery: HttpOnly {@code __Host-TradeSession} + double-submit CSRF on unsafe methods.
 * No SecurityContext ThreadLocal.
 */
public final class BearerSecurityFilter extends OncePerRequestFilter {
    private final AccessTokenAuthenticator auth;
    private final ObjectMapper json;
    private final boolean cookieSecureRequired;

    public BearerSecurityFilter(AccessTokenAuthenticator auth, ObjectMapper json, boolean cookieSecureRequired) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.json = Objects.requireNonNull(json, "json");
        this.cookieSecureRequired = cookieSecureRequired;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        securityHeaders(request, response);
        if (!requiresAuthentication(request)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            ResolvedCredential credential = resolveCredential(request);
            if (credential.fromCookie() && isUnsafe(request) && !CookieAuthSupport.xsrfMatches(request)) {
                writeError(request, response, HttpStatus.FORBIDDEN, "CSRF_REJECTED",
                        "valid X-XSRF-TOKEN required for cookie sessions");
                return;
            }
            Actor actor = auth.authenticate(credential.rawToken());
            if (request.getRequestURI().startsWith("/actuator/")) {
                actor.require(Permission.METRICS_READ);
            }
            request.setAttribute(ActorRequest.ACTOR_ATTRIBUTE, actor);
            request.setAttribute(ActorRequest.TOKEN_ATTRIBUTE, credential.rawToken());
            chain.doFilter(request, response);
        } catch (AuthenticationException exception) {
            writeError(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "valid bearer token required");
        } catch (AccessDeniedException exception) {
            writeError(request, response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", exception.getMessage());
        } catch (DataAccessException exception) {
            writeError(request, response, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "please retry later");
        }
    }

    private ResolvedCredential resolveCredential(HttpServletRequest request) {
        Optional<String> bearer = bearerToken(request);
        if (bearer.isPresent()) {
            return new ResolvedCredential(bearer.get(), false);
        }
        if (!cookieSecureRequired) {
            throw new AuthenticationException();
        }
        // Cookie sessions only accepted when Secure cookies are configured (TLS in front).
        if (!request.isSecure() && !"https".equalsIgnoreCase(forwardedProto(request))) {
            throw new AuthenticationException();
        }
        return CookieAuthSupport.read(request, CookieAuthSupport.SESSION_COOKIE)
                .map(token -> new ResolvedCredential(token, true))
                .orElseThrow(AuthenticationException::new);
    }

    private static String forwardedProto(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) {
            return "";
        }
        return proto.split(",")[0].trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isUnsafe(HttpServletRequest request) {
        String method = request.getMethod();
        return !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS") || method.equals("TRACE"));
    }

    private static boolean requiresAuthentication(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean login = path.equals("/api/v1/auth/token") && request.getMethod().equals("POST");
        boolean health = path.equals("/actuator/health") || path.startsWith("/actuator/health/");
        return !login && !health && (path.startsWith("/api/") || path.startsWith("/actuator/"));
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            return Optional.empty();
        }
        return Optional.of(header.substring(7));
    }

    private static void securityHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
                + "font-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; "
                + "form-action 'self'");
        if (request.getRequestURI().startsWith("/api/") || request.getRequestURI().startsWith("/actuator/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (request.isSecure() || "https".equalsIgnoreCase(forwardedProto(request))) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getWriter(), new ErrorResponse(code, message, request.getRequestURI()));
    }

    private record ResolvedCredential(String rawToken, boolean fromCookie) {
    }
}

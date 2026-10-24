package com.acme.trade.adapter.security;

import com.acme.trade.adapter.web.ActorRequest;
import com.acme.trade.adapter.web.ErrorResponse;
import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.exception.RateLimitExceededException;
import com.acme.trade.domain.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * Per-actor API throttle after authentication. Login remains on {@link RedisLoginThrottle}.
 * Fail-closed when Redis is down (503 LOCK_UNAVAILABLE), never silently skips the limit.
 */
public final class ApiRateLimitFilter extends OncePerRequestFilter {
    private final RedisApiRateLimiter limiter;
    private final ObjectMapper json;

    public ApiRateLimitFilter(RedisApiRateLimiter limiter, ObjectMapper json) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object actorAttr = request.getAttribute(ActorRequest.ACTOR_ATTRIBUTE);
        if (!(actorAttr instanceof Actor actor)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            limiter.check(actor.id());
            chain.doFilter(request, response);
        } catch (RateLimitExceededException exception) {
            writeError(request, response, HttpStatus.TOO_MANY_REQUESTS, exception.code(),
                    exception.getMessage(), exception.retryAfterSeconds());
        } catch (LockUnavailableException exception) {
            writeError(request, response, HttpStatus.SERVICE_UNAVAILABLE, "LOCK_UNAVAILABLE",
                    "please retry later", 0);
        }
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, String code, String message, long retryAfter) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status.value());
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
        }
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getWriter(), new ErrorResponse(code, message, request.getRequestURI()));
    }
}

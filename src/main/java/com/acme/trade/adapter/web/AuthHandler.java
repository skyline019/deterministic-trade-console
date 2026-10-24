package com.acme.trade.adapter.web;

import com.acme.trade.adapter.security.cookie.CookieAuthSupport;
import com.acme.trade.application.service.AuthService;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.ValidationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Objects;

public final class AuthHandler {
    private final AuthService auth;
    private final boolean cookieSecure;
    private final boolean passwordLoginEnabled;

    public AuthHandler(AuthService auth, boolean cookieSecure) {
        this(auth, cookieSecure, true);
    }

    public AuthHandler(AuthService auth, boolean cookieSecure, boolean passwordLoginEnabled) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.cookieSecure = cookieSecure;
        this.passwordLoginEnabled = passwordLoginEnabled;
    }

    public ServerResponse issue(ServerRequest request) throws Exception {
        if (!passwordLoginEnabled) {
            throw new AccessDeniedException("PASSWORD_LOGIN");
        }
        LoginHttpRequest body = request.body(LoginHttpRequest.class);
        if (body == null) {
            throw new ValidationException("JSON request body is required");
        }
        String client = request.remoteAddress()
                .map(address -> address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress())
                .orElse("unknown");
        var issued = auth.issue(body.username(), body.password(), client);
        if (CookieAuthSupport.isCookieDelivery(body.delivery())) {
            if (!cookieSecure) {
                throw new ValidationException(
                        "cookie delivery requires trade.security.cookie.secure=true (TLS terminator)");
            }
            String xsrf = CookieAuthSupport.newXsrfToken();
            ResponseCookie session = CookieAuthSupport.sessionCookie(issued.rawToken(), issued.expiresAt(), true);
            ResponseCookie xsrfCookie = CookieAuthSupport.xsrfCookie(xsrf, issued.expiresAt(), true);
            return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.add(HttpHeaders.SET_COOKIE, session.toString());
                        headers.add(HttpHeaders.SET_COOKIE, xsrfCookie.toString());
                    })
                    .body(TokenResponse.cookie(issued));
        }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(TokenResponse.bearer(issued));
    }

    public ServerResponse me(ServerRequest request) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .body(ActorResponse.from(ActorRequest.actor(request)));
    }

    public ServerResponse revoke(ServerRequest request) {
        auth.revoke(ActorRequest.rawToken(request));
        ResponseCookie clearSession = CookieAuthSupport.clearSession(cookieSecure);
        ResponseCookie clearXsrf = CookieAuthSupport.clearXsrf(cookieSecure);
        return ServerResponse.noContent()
                .headers(headers -> {
                    headers.add(HttpHeaders.SET_COOKIE, clearSession.toString());
                    headers.add(HttpHeaders.SET_COOKIE, clearXsrf.toString());
                })
                .build();
    }
}

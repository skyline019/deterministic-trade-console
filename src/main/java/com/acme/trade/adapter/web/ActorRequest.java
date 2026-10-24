package com.acme.trade.adapter.web;

import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.domain.security.Actor;
import org.springframework.web.servlet.function.ServerRequest;

public final class ActorRequest {
    public static final String ACTOR_ATTRIBUTE = "trade.actor";
    public static final String TOKEN_ATTRIBUTE = "trade.raw-token";

    private ActorRequest() {
    }

    public static Actor actor(ServerRequest request) {
        Object actor = request.attributes().get(ACTOR_ATTRIBUTE);
        if (actor instanceof Actor identity) {
            return identity;
        }
        throw new AuthenticationException();
    }

    public static String rawToken(ServerRequest request) {
        Object token = request.attributes().get(TOKEN_ATTRIBUTE);
        if (token instanceof String value) {
            return value;
        }
        throw new AuthenticationException();
    }
}

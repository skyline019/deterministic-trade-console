package com.acme.trade.adapter.web;

import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;

import java.util.List;

public record ActorResponse(String id, String username, List<String> roles) {
    public static ActorResponse from(Actor actor) {
        return new ActorResponse(actor.id(), actor.username(), actor.roles().stream().map(Role::name).sorted().toList());
    }
}

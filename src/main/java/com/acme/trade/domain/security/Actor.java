package com.acme.trade.domain.security;

import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.ValidationException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Authenticated identity passed explicitly across the application boundary; no ThreadLocal. */
public record Actor(String id, String username, Set<Role> roles) {
    public Actor {
        if (id == null || id.isBlank() || username == null || username.isBlank()) {
            throw new ValidationException("actor identity is required");
        }
        Objects.requireNonNull(roles, "roles");
        roles = roles.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    public void require(Permission permission) {
        Objects.requireNonNull(permission, "permission");
        boolean allowed = roles.stream().anyMatch(role -> role.allows(permission));
        if (!allowed) {
            throw new AccessDeniedException(permission.name());
        }
    }

    public boolean has(Permission permission) {
        return roles.stream().anyMatch(role -> role.allows(permission));
    }
}

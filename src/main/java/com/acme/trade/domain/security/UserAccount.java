package com.acme.trade.domain.security;

import com.acme.trade.domain.exception.ValidationException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Credentials holder with deliberately no toString(): password hashes must not be logged. */
public final class UserAccount {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,31}");

    private final UUID id;
    private final String username;
    private final String passwordHash;
    private final boolean enabled;
    private final Set<Role> roles;

    public UserAccount(UUID id, String username, String passwordHash, boolean enabled, Set<Role> roles) {
        this.id = Objects.requireNonNull(id, "id");
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ValidationException("username must match [A-Za-z0-9][A-Za-z0-9._-]{2,31}");
        }
        this.username = username;
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.enabled = enabled;
        Objects.requireNonNull(roles, "roles");
        this.roles = roles.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    public UUID id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<Role> roles() {
        return roles;
    }

    public Actor actor() {
        return new Actor(id.toString(), username, roles);
    }
}

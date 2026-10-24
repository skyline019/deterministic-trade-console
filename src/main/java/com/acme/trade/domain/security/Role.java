package com.acme.trade.domain.security;

import java.util.EnumSet;
import java.util.Set;

public enum Role {
    ADMIN(EnumSet.allOf(Permission.class)),
    OPERATOR(EnumSet.of(Permission.INVENTORY_READ, Permission.INVENTORY_WRITE,
            Permission.ORDER_READ, Permission.ORDER_WRITE)),
    VIEWER(EnumSet.of(Permission.INVENTORY_READ, Permission.ORDER_READ)),
    AUDITOR(EnumSet.of(Permission.INVENTORY_READ, Permission.ORDER_READ,
            Permission.AUDIT_READ, Permission.METRICS_READ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean allows(Permission permission) {
        return permissions.contains(permission);
    }
}

package com.acme.trade.adapter.security;

import com.acme.trade.application.service.AdminBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;

/** Explicit one-shot startup action; credentials are never included in logs or toString(). */
public final class AdminBootstrapRunner implements ApplicationRunner {
    private final AdminBootstrapService service;
    private final String username;
    private final String password;

    public AdminBootstrapRunner(AdminBootstrapService service, String username, String password) {
        this.service = Objects.requireNonNull(service, "service");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
    }

    @Override
    public void run(ApplicationArguments args) {
        service.ensureAdmin(username, password);
    }
}

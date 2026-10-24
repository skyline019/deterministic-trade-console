package com.acme.trade.adapter.web;

import com.acme.trade.application.service.DashboardService;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Objects;

public final class DashboardHandler {
    private final DashboardService dashboard;

    public DashboardHandler(DashboardService dashboard) {
        this.dashboard = Objects.requireNonNull(dashboard, "dashboard");
    }

    public ServerResponse get(ServerRequest request) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .body(dashboard.load(ActorRequest.actor(request)));
    }
}

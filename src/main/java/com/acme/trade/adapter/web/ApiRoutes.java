package com.acme.trade.adapter.web;

import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

public final class ApiRoutes {
    private ApiRoutes() {
    }

    public static RouterFunction<ServerResponse> routes(
            AuthHandler auth, OrderHandler orders, InventoryHandler inventory,
            AuditHandler audits, DashboardHandler dashboard) {
        return RouterFunctions.route()
                .POST("/api/v1/auth/token", auth::issue)
                .DELETE("/api/v1/auth/token", auth::revoke)
                .GET("/api/v1/auth/me", auth::me)
                .GET("/api/v1/dashboard", dashboard::get)
                .POST("/api/v1/orders", orders::place)
                .GET("/api/v1/orders", orders::list)
                .POST("/api/v1/orders/{id}/confirmation", orders::confirm)
                .POST("/api/v1/orders/{id}/cancellation", orders::cancel)
                .POST("/api/v1/orders/{id}/reschedule", orders::reschedule)
                .GET("/api/v1/orders/{id}", orders::get)
                .POST("/api/v1/inventory", inventory::create)
                .GET("/api/v1/inventory", inventory::list)
                .POST("/api/v1/inventory/{sku}/adjustments", inventory::adjust)
                .GET("/api/v1/inventory/{sku}", inventory::get)
                .GET("/api/v1/audit/events", audits::events)
                .GET("/api/v1/audit/verification", audits::verify)
                .filter(ApiErrorHandler::filter)
                .build();
    }
}

package com.acme.trade.adapter.web;

import com.acme.trade.application.service.AuditService;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.exception.ValidationException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Objects;

public final class AuditHandler {
    private final AuditService audits;

    public AuditHandler(AuditService audits) {
        this.audits = Objects.requireNonNull(audits, "audits");
    }

    public ServerResponse events(ServerRequest request) {
        AggregateRef aggregate = aggregate(request);
        int limit = HttpQuery.integer(request, "limit", 100);
        var events = audits.events(ActorRequest.actor(request), aggregate, limit)
                .stream().map(AuditEventResponse::from).toList();
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(events);
    }

    public ServerResponse verify(ServerRequest request) {
        var trusted = request.param("trustedCheckpointHash").filter(value -> !value.isBlank());
        var result = audits.verify(ActorRequest.actor(request), aggregate(request), trusted);
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(result);
    }

    private static AggregateRef aggregate(ServerRequest request) {
        String type = HttpQuery.required(request, "type");
        String id = HttpQuery.required(request, "id");
        try {
            return AggregateRef.of(AggregateType.valueOf(type), id);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("unknown audit aggregate type");
        }
    }
}

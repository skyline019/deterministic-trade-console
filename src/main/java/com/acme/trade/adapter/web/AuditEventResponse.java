package com.acme.trade.adapter.web;

import com.acme.trade.domain.audit.AuditEvent;

import java.time.Instant;

public record AuditEventResponse(
        String id, String aggregateType, String aggregateId, long sequence, String action,
        String actorId, String actorName, String requestId, Instant occurredAt,
        String payloadJson, String previousHash, String eventHash) {
    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.id().toString(), event.aggregate().type().name(), event.aggregate().id(),
                event.sequence(), event.action(), event.actorId(), event.actorName(), event.requestId().value(),
                event.occurredAt(), event.payloadJson(), event.previousHash(), event.eventHash());
    }
}

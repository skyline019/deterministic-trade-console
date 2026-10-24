package com.acme.trade.application.port;

import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.security.Actor;

import java.util.Map;

/** Called inside an existing business transaction; implementations must not start a new one. */
public interface AuditRecorder {
    void record(Actor actor, String action, AggregateRef aggregate,
                RequestId requestId, Map<String, String> payload);
}

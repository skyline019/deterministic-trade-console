package com.acme.trade.application.service;

import com.acme.trade.application.port.AuditIdGenerator;
import com.acme.trade.application.port.AuditRecorder;
import com.acme.trade.application.port.AuditRepository;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AuditChainVerifier;
import com.acme.trade.domain.audit.AuditEvent;
import com.acme.trade.domain.audit.AuditVerification;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Permission;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AuditService implements AuditRecorder {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final AuditRepository audits;
    private final AuditIdGenerator ids;
    private final Clock clock;
    private final TransactionRunner transactions;

    public AuditService(AuditRepository audits, AuditIdGenerator ids, Clock clock, TransactionRunner transactions) {
        this.audits = Objects.requireNonNull(audits, "audits");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void record(Actor actor, String action, AggregateRef aggregate,
                       RequestId requestId, Map<String, String> payload) {
        var previous = audits.findLast(aggregate);
        AuditEvent event = AuditEvent.append(ids.newId(), aggregate, previous, action,
                actor, requestId, clock.instant(), payload);
        audits.insert(event);
    }

    public void appendCheckpoint(Actor actor, AggregateRef aggregate, RequestId requestId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(requestId, "requestId");
        transactions.inTransaction(TransactionMode.WRITE, () -> {
            var previous = audits.findLast(aggregate);
            if (previous.isEmpty()) {
                throw new ValidationException("cannot checkpoint an empty audit chain");
            }
            AuditEvent tip = previous.get();
            AuditEvent event = AuditEvent.append(ids.newId(), aggregate, previous,
                    AuditChainVerifier.CHECKPOINT_ACTION, actor, requestId, clock.instant(),
                    Map.of(
                            "anchoredSequence", Long.toString(tip.sequence()),
                            "anchoredHash", tip.eventHash()));
            audits.insert(event);
            return null;
        });
    }

    public List<AuditEvent> events(Actor actor, AggregateRef aggregate, int limit) {
        actor.require(Permission.AUDIT_READ);
        if (limit < 1 || limit > 500) {
            throw new ValidationException("audit limit must be between 1 and 500");
        }
        return transactions.inTransaction(TransactionMode.READ_ONLY,
                () -> audits.findRecent(aggregate, limit));
    }

    public AuditVerification verify(Actor actor, AggregateRef aggregate) {
        return verify(actor, aggregate, Optional.empty());
    }

    public AuditVerification verify(Actor actor, AggregateRef aggregate, Optional<String> trustedCheckpointHash) {
        actor.require(Permission.AUDIT_READ);
        Objects.requireNonNull(trustedCheckpointHash, "trustedCheckpointHash");
        return transactions.inTransaction(TransactionMode.READ_ONLY, () -> {
            if (trustedCheckpointHash.isEmpty()) {
                return AuditChainVerifier.verify(audits.findChain(aggregate));
            }
            String hash = trustedCheckpointHash.get().trim().toLowerCase(Locale.ROOT);
            if (!HASH.matcher(hash).matches()) {
                throw new ValidationException("trustedCheckpointHash must be a lowercase SHA-256 digest");
            }
            Optional<AuditEvent> checkpoint = audits.findByEventHash(aggregate, hash);
            if (checkpoint.isEmpty()) {
                return new AuditVerification(false, 0, "trusted checkpoint hash not found");
            }
            List<AuditEvent> segment = audits.findFromSequence(aggregate, checkpoint.get().sequence());
            return AuditChainVerifier.verifySinceTrusted(segment, hash);
        });
    }
}

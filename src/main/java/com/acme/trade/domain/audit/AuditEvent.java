package com.acme.trade.domain.audit;

import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.security.Actor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** One tamper-evident event in an aggregate-local hash chain. */
public record AuditEvent(
        UUID id,
        AggregateRef aggregate,
        long sequence,
        String action,
        String actorId,
        String actorName,
        RequestId requestId,
        Instant occurredAt,
        String payloadJson,
        String previousHash,
        String eventHash) {

    public static final String GENESIS_HASH = "0".repeat(64);
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (sequence < 1 || !ACTION.matcher(action).matches()) {
            throw new ValidationException("invalid audit sequence or action");
        }
        if (!HASH.matcher(previousHash).matches() || !HASH.matcher(eventHash).matches()) {
            throw new ValidationException("audit hashes must be lowercase SHA-256 digests");
        }
    }

    public static AuditEvent append(
            UUID id,
            AggregateRef aggregate,
            Optional<AuditEvent> previous,
            String action,
            Actor actor,
            RequestId requestId,
            Instant occurredAt,
            Map<String, String> payload) {
        previous.ifPresent(event -> {
            if (!aggregate.equals(event.aggregate())) {
                throw new ValidationException("cannot append an event across aggregate chains");
            }
        });
        long sequence = previous.map(event -> event.sequence + 1).orElse(1L);
        String previousHash = previous.map(AuditEvent::eventHash).orElse(GENESIS_HASH);
        String payloadJson = CanonicalJson.of(payload);
        // Persistable precision: MySQL TIMESTAMP(6) cannot round-trip nanoseconds into the hash material.
        Instant boundedOccurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        String eventHash = hash(id, aggregate, sequence, action, actor, requestId, boundedOccurredAt,
                payloadJson, previousHash);
        return new AuditEvent(id, aggregate, sequence, action, actor.id(), actor.username(), requestId,
                boundedOccurredAt, payloadJson, previousHash, eventHash);
    }

    public boolean hasValidLinkAndHash(Optional<AuditEvent> previous) {
        long expectedSequence = previous.map(event -> event.sequence + 1).orElse(1L);
        String expectedPrevious = previous.map(AuditEvent::eventHash).orElse(GENESIS_HASH);
        return previous.map(event -> aggregate.equals(event.aggregate())).orElse(true)
                && sequence == expectedSequence
                && previousHash.equals(expectedPrevious)
                && eventHash.equals(calculateHash());
    }

    public String calculateHash() {
        return hash(id, aggregate, sequence, action,
                new Actor(actorId, actorName, java.util.Set.of()), requestId, occurredAt,
                payloadJson, previousHash);
    }

    private static String hash(
            UUID id,
            AggregateRef aggregate,
            long sequence,
            String action,
            Actor actor,
            RequestId requestId,
            Instant occurredAt,
            String payloadJson,
            String previousHash) {
        StringBuilder material = new StringBuilder("trade-audit-v1;");
        field(material, id.toString());
        field(material, aggregate.type().name());
        field(material, aggregate.id());
        field(material, Long.toString(sequence));
        field(material, action);
        field(material, actor.id());
        field(material, actor.username());
        field(material, requestId.value());
        field(material, occurredAt.toString());
        field(material, payloadJson);
        field(material, previousHash);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is mandatory in Java", exception);
        }
    }

    private static void field(StringBuilder material, String value) {
        material.append(value.length()).append(':').append(value).append(';');
    }
}

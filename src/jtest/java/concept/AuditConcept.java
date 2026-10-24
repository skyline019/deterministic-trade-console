package concept;

import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.audit.AuditChainVerifier;
import com.acme.trade.domain.audit.AuditEvent;
import com.acme.trade.domain.audit.CanonicalJson;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.RequestId;
import jtest.Concept;
import jtest.JTestRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static concept.ConceptFixture.NOW;
import static concept.ConceptFixture.OPERATOR;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectFalse;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class AuditConcept implements Concept {
    private static final AggregateRef ORDER = AggregateRef.of(AggregateType.ORDER,
            "00000000-0000-0000-0000-000000000001");

    @Override
    public String name() {
        return "AuditIntegrity";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("canonical_json_sorts_keys_and_escapes_content", () -> {
            expectEquals("{\"a\":\"line\\n\",\"z\":\"\\\"quoted\\\"\"}",
                    CanonicalJson.of(Map.of("z", "\"quoted\"", "a", "line\n")));
        });

        tests.test("empty_and_valid_chains_have_precise_verification_results", () -> {
            expectTrue(AuditChainVerifier.verify(List.of()).valid());
            expectEquals(0, AuditChainVerifier.verify(List.of()).checkedEvents());
            AuditEvent first = event(11, ORDER, Optional.empty(), "ORDER_RESERVED", "audit-1");
            AuditEvent second = event(12, ORDER, Optional.of(first), "ORDER_CONFIRMED", "audit-2");
            var result = AuditChainVerifier.verify(List.of(first, second));
            expectTrue(result.valid());
            expectEquals(2, result.checkedEvents());
            expectTrue(result.message().contains("sequence 2"));
        });

        tests.test("every_hash_bound_field_tamper_is_detected", () -> {
            AuditEvent first = event(11, ORDER, Optional.empty(), "ORDER_RESERVED", "audit-1");
            AuditEvent second = event(12, ORDER, Optional.of(first), "ORDER_CONFIRMED", "audit-2");
            List<AuditEvent> tampered = List.of(
                    new AuditEvent(UUID.randomUUID(), first.aggregate(), first.sequence(), first.action(), first.actorId(), first.actorName(), first.requestId(), first.occurredAt(), first.payloadJson(), first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), AggregateRef.of(AggregateType.INVENTORY, "SKU-1"), first.sequence(), first.action(), first.actorId(), first.actorName(), first.requestId(), first.occurredAt(), first.payloadJson(), first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), 2, first.action(), first.actorId(), first.actorName(), first.requestId(), first.occurredAt(), first.payloadJson(), first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), first.sequence(), "ORDER_CANCELLED", first.actorId(), first.actorName(), first.requestId(), first.occurredAt(), first.payloadJson(), first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), first.sequence(), first.action(), "another-actor", first.actorName(), first.requestId(), first.occurredAt(), first.payloadJson(), first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), first.sequence(), first.action(), first.actorId(), "another-name", first.requestId(), first.occurredAt(), first.payloadJson(), first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), first.sequence(), first.action(), first.actorId(), first.actorName(), new RequestId("another-request"), first.occurredAt(), first.payloadJson(), first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), first.sequence(), first.action(), first.actorId(), first.actorName(), first.requestId(), NOW.plusSeconds(9), first.payloadJson(), first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), first.sequence(), first.action(), first.actorId(), first.actorName(), first.requestId(), first.occurredAt(), "{\"quantity\":\"99\"}", first.previousHash(), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), first.sequence(), first.action(), first.actorId(), first.actorName(), first.requestId(), first.occurredAt(), first.payloadJson(), "1".repeat(64), first.eventHash()),
                    new AuditEvent(first.id(), first.aggregate(), first.sequence(), first.action(), first.actorId(), first.actorName(), first.requestId(), first.occurredAt(), first.payloadJson(), first.previousHash(), "0".repeat(64))
            );

            for (AuditEvent candidate : tampered) {
                expectFalse(AuditChainVerifier.verify(List.of(candidate, second)).valid());
            }
        });

        tests.test("mixed_aggregates_null_events_gaps_and_reordering_fail_closed", () -> {
            AuditEvent first = event(11, ORDER, Optional.empty(), "ORDER_RESERVED", "audit-1");
            AuditEvent second = event(12, ORDER, Optional.of(first), "ORDER_CONFIRMED", "audit-2");
            AuditEvent inventory = event(13, AggregateRef.of(AggregateType.INVENTORY, "SKU-1"),
                    Optional.empty(), "STOCK_CREATED", "audit-3");

            expectFalse(AuditChainVerifier.verify(List.of(first, inventory)).valid());
            expectFalse(AuditChainVerifier.verify(Arrays.asList(first, null)).valid());
            expectFalse(AuditChainVerifier.verify(List.of(second)).valid());
            expectFalse(AuditChainVerifier.verify(List.of(second, first)).valid());
            expectFalse(AuditChainVerifier.verify(List.of(first, first)).valid());
            expectThrows(ValidationException.class, () -> event(14, ORDER, Optional.of(inventory), "ORDER_CONFIRMED", "cross"));
            expectThrows(NullPointerException.class, () -> AuditChainVerifier.verify(null));
        });

        tests.test("thousand_event_chain_verifies_iteratively_without_recursion", () -> {
            List<AuditEvent> chain = new ArrayList<>();
            Optional<AuditEvent> previous = Optional.empty();
            for (int sequence = 1; sequence <= 1_000; sequence++) {
                AuditEvent next = AuditEvent.append(new UUID(0, sequence), ORDER, previous, "ORDER_RESERVED",
                        OPERATOR, new RequestId("long-chain-" + sequence), NOW.plusNanos(sequence), Map.of("n", Integer.toString(sequence)));
                chain.add(next);
                previous = Optional.of(next);
            }

            var result = AuditChainVerifier.verify(chain);

            expectTrue(result.valid());
            expectEquals(1_000, result.checkedEvents());
            expectEquals(1_000L, chain.get(chain.size() - 1).sequence());
        });

        tests.test("trusted_checkpoint_verifies_suffix_without_replaying_prefix", () -> {
            AuditEvent first = event(21, ORDER, Optional.empty(), "ORDER_RESERVED", "cp-1");
            AuditEvent second = event(22, ORDER, Optional.of(first), "ORDER_CONFIRMED", "cp-2");
            AuditEvent checkpoint = AuditEvent.append(new UUID(0, 23), ORDER, Optional.of(second),
                    AuditChainVerifier.CHECKPOINT_ACTION, OPERATOR, new RequestId("cp-check"),
                    NOW, Map.of("anchoredSequence", "2", "anchoredHash", second.eventHash()));
            AuditEvent after = event(24, ORDER, Optional.of(checkpoint), "ORDER_CANCELLED", "cp-4");

            var full = AuditChainVerifier.verify(List.of(first, second, checkpoint, after));
            expectTrue(full.valid());

            var since = AuditChainVerifier.verifySinceTrusted(
                    List.of(checkpoint, after), checkpoint.eventHash());
            expectTrue(since.valid());
            expectEquals(1, since.checkedEvents());
            expectTrue(since.message().contains("since-checkpoint"));

            expectFalse(AuditChainVerifier.verifySinceTrusted(
                    List.of(first, second), second.eventHash()).valid());
            expectFalse(AuditChainVerifier.verifySinceTrusted(
                    List.of(checkpoint, after), "a".repeat(64)).valid());
        });
    }

    private static AuditEvent event(int id, AggregateRef aggregate, Optional<AuditEvent> previous,
                                    String action, String requestId) {
        return AuditEvent.append(new UUID(0, id), aggregate, previous, action, OPERATOR,
                new RequestId(requestId), NOW, Map.of("quantity", "3"));
    }
}

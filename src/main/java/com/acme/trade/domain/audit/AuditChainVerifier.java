package com.acme.trade.domain.audit;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AuditChainVerifier {
    public static final String CHECKPOINT_ACTION = "CHAIN_CHECKPOINT";

    private AuditChainVerifier() {
    }

    public static AuditVerification verify(List<AuditEvent> events) {
        Objects.requireNonNull(events, "events");
        return verifyRange(events, 0, Optional.empty(), "full");
    }

    /**
     * Incremental verify: treat an out-of-band attested {@code CHAIN_CHECKPOINT} eventHash as trusted,
     * then check only the suffix after it. The checkpoint itself must be present, self-consistent,
     * and carry action {@link #CHECKPOINT_ACTION}.
     */
    public static AuditVerification verifySinceTrusted(List<AuditEvent> events, String trustedCheckpointHash) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(trustedCheckpointHash, "trustedCheckpointHash");
        int checkpointIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            AuditEvent event = events.get(i);
            if (event != null && trustedCheckpointHash.equals(event.eventHash())) {
                checkpointIndex = i;
                break;
            }
        }
        if (checkpointIndex < 0) {
            return new AuditVerification(false, 0, "trusted checkpoint hash not found in chain segment");
        }
        AuditEvent checkpoint = events.get(checkpointIndex);
        if (!CHECKPOINT_ACTION.equals(checkpoint.action())) {
            return new AuditVerification(false, 1, "trusted hash does not identify a CHAIN_CHECKPOINT event");
        }
        if (!checkpoint.eventHash().equals(checkpoint.calculateHash())) {
            return new AuditVerification(false, 1, "trusted checkpoint failed self-hash check");
        }
        if (checkpointIndex + 1 >= events.size()) {
            return new AuditVerification(true, 1,
                    "checkpoint at sequence " + checkpoint.sequence() + " trusted; no suffix events");
        }
        return verifyRange(events, checkpointIndex + 1, Optional.of(checkpoint), "since-checkpoint");
    }

    private static AuditVerification verifyRange(List<AuditEvent> events, int fromInclusive,
                                                 Optional<AuditEvent> seedPrevious, String mode) {
        Optional<AuditEvent> previous = seedPrevious;
        AggregateRef aggregate = seedPrevious.map(AuditEvent::aggregate).orElse(null);
        int checked = 0;
        for (int i = fromInclusive; i < events.size(); i++) {
            checked++;
            AuditEvent event = events.get(i);
            if (event == null) {
                return new AuditVerification(false, checked, "chain contains a null event");
            }
            if (aggregate == null) {
                aggregate = event.aggregate();
            } else if (!aggregate.equals(event.aggregate())) {
                return new AuditVerification(false, checked, "chain contains more than one aggregate");
            }
            if (!event.hasValidLinkAndHash(previous)) {
                return new AuditVerification(false, checked,
                        "chain verification failed at sequence " + event.sequence());
            }
            previous = Optional.of(event);
        }
        if (checked == 0) {
            return new AuditVerification(true, 0, "no events");
        }
        long tip = previous.orElseThrow().sequence();
        return new AuditVerification(true, checked, mode + " chain verified through sequence " + tip);
    }
}

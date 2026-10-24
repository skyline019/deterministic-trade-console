package concept;

import jtest.JTestRunner;

import java.util.List;

/** Explicit suite list: no classpath scanning and no collision with JUnit's discovery. */
public final class ConceptEntrypoint {
    private ConceptEntrypoint() {
    }

    public static void main(String[] args) {
        int failures = JTestRunner.run(List.of(
                new DomainBoundaryConcept(),
                new ServiceCapabilityConcept(),
                new OrderPlacementConcept(),
                new OrderLifecycleConcept(),
                new InventoryCommandConcept(),
                new CompositeWorkflowConcept(),
                new OrderQueryConcept(),
                new FailureAtomicityConcept(),
                new AuditConcept(),
                new AuthConcept(),
                new BootstrapConcept(),
                new AdapterAndConfigurationConcept(),
                new CookieAuthConcept(),
                new ReservationExpiryConcept(),
                new JwtAuthConcept(),
                new JwksAuthConcept(),
                new MetricsConcept(),
                new HighPressureConcept(),
                new PartialConfirmConcept(),
                new ReservationRescheduleConcept()), System.out);
        if (failures != 0) {
            throw new AssertionError("jtest failed: " + failures + " concept check(s)");
        }
    }
}

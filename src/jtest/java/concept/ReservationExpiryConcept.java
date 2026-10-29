package concept;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.port.AuditRepository;
import com.acme.trade.application.port.MaintenanceStore;
import com.acme.trade.application.result.SweepReport;
import com.acme.trade.application.service.AuditService;
import com.acme.trade.application.service.MaintenanceService;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AuditEvent;
import com.acme.trade.domain.exception.OrderStateConflictException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;
import jtest.Concept;
import jtest.JTestRegistry;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static concept.ConceptFixture.CLOCK;
import static concept.ConceptFixture.NOW;
import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectFalse;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

/** Expired RESERVED orders cancel through OrderTransitionService (same ledger as manual cancel). */
public final class ReservationExpiryConcept implements Concept {
    @Override
    public String name() {
        return "ReservationExpiry";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("expire_reservations_releases_stock_via_cancel_path", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Quantity qty = new Quantity(3);
            expectTrue(fixture.inventory.tryReserve(SKU, qty, NOW));
            Order expired = Order.reserved(OrderId.random(), new RequestId("expire-src"), SKU, qty,
                    NOW.minusSeconds(7200), NOW.minusSeconds(60));
            fixture.orders.insert(expired);
            expectEquals(3, fixture.reserved());
            expectEquals(7, fixture.available());

            MaintenanceService maintenance = new MaintenanceService(noopStore(), auditService(fixture),
                    fixture.orders, fixture.transitions, CLOCK);
            SweepReport report = maintenance.expireReservations(10);
            expectEquals(1, report.applied());
            expectTrue(report.complete());
            Order after = fixture.orders.findById(expired.id()).orElseThrow();
            expectEquals(OrderStatus.CANCELLED, after.status());
            expectEquals(0, fixture.reserved());
            expectEquals(10, fixture.available());

            expectEquals(0, maintenance.expireReservations(10).applied());
            fixture.transitions.transition(
                    new Actor("system-expiry", "system-expiry", EnumSet.of(Role.OPERATOR)),
                    new RequestId("expire-" + expired.id()),
                    expired.id(),
                    OrderStatus.CANCELLED,
                    "reservation-expired");
            expectEquals(0, fixture.reserved());
            expectEquals(10, fixture.available());
        });

        tests.test("non_expired_reserved_orders_are_not_touched", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(5);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "fresh-reserve", SKU.value(), 2));
            expectEquals(OrderStatus.RESERVED, placed.order().status());
            expectTrue(placed.order().expiresAt().isAfter(NOW));

            MaintenanceService maintenance = new MaintenanceService(noopStore(), auditService(fixture),
                    fixture.orders, fixture.transitions, CLOCK);
            expectEquals(0, maintenance.expireReservations(10).applied());
            expectEquals(OrderStatus.RESERVED, fixture.orders.findById(placed.order().id()).orElseThrow().status());
        });

        tests.test("deadline_is_a_hard_boundary_for_confirm", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Quantity qty = new Quantity(4);
            expectTrue(fixture.inventory.tryReserve(SKU, qty, NOW));
            Order expired = Order.reserved(OrderId.random(), new RequestId("expired-confirm"), SKU, qty,
                    NOW.minusSeconds(7200), NOW.minusSeconds(60));
            fixture.orders.insert(expired);

            // Confirming before the operator sweep must fail exactly like confirming after it,
            // otherwise the outcome depends on when maintenance last ran.
            expectThrows(OrderStateConflictException.class, () -> fixture.transitions.confirm(
                    OPERATOR, new RequestId("confirm-expired"), expired.id(), null, "manual-confirm"));
            expectEquals(OrderStatus.RESERVED, fixture.orders.findById(expired.id()).orElseThrow().status());
            expectEquals(4, fixture.reserved());
            expectEquals(6, fixture.available());

            MaintenanceService maintenance = new MaintenanceService(noopStore(), auditService(fixture),
                    fixture.orders, fixture.transitions, CLOCK);
            expectEquals(1, maintenance.expireReservations(10).applied());
            expectEquals(0, fixture.reserved());
            expectEquals(10, fixture.available());
        });

        tests.test("an_unreadable_row_is_reported_without_wedging_the_sweep", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Quantity qty = new Quantity(2);
            expectTrue(fixture.inventory.tryReserve(SKU, qty, NOW));
            Order expired = Order.reserved(OrderId.random(), new RequestId("wedge-src"), SKU, qty,
                    NOW.minusSeconds(7200), NOW.minusSeconds(60));
            fixture.orders.insert(expired);
            // A stored row the domain refuses to materialise (pre-V7 corruption) must cost the sweep
            // one slot, not the whole batch.
            fixture.orders.unreadableRows.add(
                    "stored order 00000000-0000-0000-0000-00000000dead violates a domain invariant: "
                            + "reserved order expiresAt must be after createdAt");

            MaintenanceService maintenance = new MaintenanceService(noopStore(), auditService(fixture),
                    fixture.orders, fixture.transitions, CLOCK);
            SweepReport report = maintenance.expireReservations(10);
            expectEquals(2, report.attempted());
            expectEquals(1, report.applied());
            expectEquals(1, report.failed());
            expectFalse(report.complete());
            expectTrue(report.failures().get(0).contains("violates a domain invariant"));

            // The healthy reservation was still released through the normal cancel ledger.
            expectEquals(OrderStatus.CANCELLED, fixture.orders.findById(expired.id()).orElseThrow().status());
            expectEquals(0, fixture.reserved());
            expectEquals(10, fixture.available());
        });

        tests.test("one_racing_order_does_not_abort_the_sweep", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            for (String seeded : List.of("sweep-a", "sweep-b")) {
                Quantity qty = new Quantity(2);
                expectTrue(fixture.inventory.tryReserve(SKU, qty, NOW));
                fixture.orders.insert(Order.reserved(OrderId.random(), new RequestId(seeded), SKU, qty,
                        NOW.minusSeconds(7200), NOW.minusSeconds(60)));
            }
            // One selected order loses a race on its receipt insert; the sweep must still cancel the rest.
            fixture.orderOperations.insertFailure =
                    new RequestInProgressException("sweep-a");

            MaintenanceService maintenance = new MaintenanceService(noopStore(), auditService(fixture),
                    fixture.orders, fixture.transitions, CLOCK);
            SweepReport report = maintenance.expireReservations(10);
            expectEquals(2, report.attempted());
            expectEquals(1, report.applied());
            expectEquals(1, report.failed());
            expectFalse(report.complete());
            expectEquals(2, fixture.reserved());

            // The healthy order was cancelled and its reservation released exactly once.
            expectEquals(1, maintenance.expireReservations(10).applied());
            expectEquals(0, fixture.reserved());
            expectEquals(10, fixture.available());
        });
    }

    private static MaintenanceStore noopStore() {
        return new MaintenanceStore() {
            @Override
            public int deleteExpiredTokens(Instant now, int limit) {
                return 0;
            }

            @Override
            public int archiveQuietAggregates(Instant cutoff, Instant archivedAt, int maxAggregates) {
                return 0;
            }

            @Override
            public List<AggregateRef> listCheckpointCandidates(int minEventsSinceCheckpoint, int maxAggregates) {
                return List.of();
            }
        };
    }

    private static AuditService auditService(ConceptFixture fixture) {
        AuditRepository repo = new AuditRepository() {
            @Override
            public Optional<AuditEvent> findLast(AggregateRef aggregate) {
                return Optional.empty();
            }

            @Override
            public List<AuditEvent> findChain(AggregateRef aggregate) {
                return List.of();
            }

            @Override
            public List<AuditEvent> findRecent(AggregateRef aggregate, int limit) {
                return List.of();
            }

            @Override
            public List<AuditEvent> findFromSequence(AggregateRef aggregate, long fromSequenceInclusive) {
                return List.of();
            }

            @Override
            public Optional<AuditEvent> findByEventHash(AggregateRef aggregate, String eventHash) {
                return Optional.empty();
            }

            @Override
            public void insert(AuditEvent event) {
            }
        };
        return new AuditService(repo, UUID::randomUUID, CLOCK, fixture.transactions);
    }
}

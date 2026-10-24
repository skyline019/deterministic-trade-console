package concept;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.port.AuditRepository;
import com.acme.trade.application.port.MaintenanceStore;
import com.acme.trade.application.service.AuditService;
import com.acme.trade.application.service.MaintenanceService;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AuditEvent;
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
            int affected = maintenance.expireReservations(10);
            expectEquals(1, affected);
            Order after = fixture.orders.findById(expired.id()).orElseThrow();
            expectEquals(OrderStatus.CANCELLED, after.status());
            expectEquals(0, fixture.reserved());
            expectEquals(10, fixture.available());

            expectEquals(0, maintenance.expireReservations(10));
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
            expectEquals(0, maintenance.expireReservations(10));
            expectEquals(OrderStatus.RESERVED, fixture.orders.findById(placed.order().id()).orElseThrow().status());
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

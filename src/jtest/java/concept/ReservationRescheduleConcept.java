package concept;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.domain.exception.OrderStateConflictException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import jtest.Concept;
import jtest.JTestRegistry;

import java.time.Instant;

import static concept.ConceptFixture.NOW;
import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

/** ADR 003: change expiresAt on live RESERVED orders within TTL window. */
public final class ReservationRescheduleConcept implements Concept {
    @Override
    public String name() {
        return "ReservationReschedule";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("reschedule_updates_expires_at_and_audits", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(5);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "rs-place", SKU.value(), 2));
            Instant previous = placed.order().expiresAt();
            Instant next = NOW.plusSeconds(1800);
            var result = fixture.reschedules.reschedule(OPERATOR, new RequestId("rs-ok"),
                    placed.order().id(), next, "extend-half-hour");
            expectEquals(false, result.replayed());
            expectEquals(OrderStatus.RESERVED, result.order().status());
            expectEquals(next, result.order().expiresAt());
            expectTrue(!result.order().expiresAt().equals(previous));
            expectTrue(fixture.audit.snapshot().contains("ORDER_RESCHEDULED"));
            expectEquals(3, fixture.available());
            expectEquals(2, fixture.reserved());
        });

        tests.test("expires_at_not_after_now_or_beyond_ttl_is_rejected", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(5);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "rs-bad-place", SKU.value(), 1));
            expectThrows(ValidationException.class, () -> fixture.reschedules.reschedule(OPERATOR,
                    new RequestId("rs-past"), placed.order().id(), NOW, "past"));
            expectThrows(ValidationException.class, () -> fixture.reschedules.reschedule(OPERATOR,
                    new RequestId("rs-over"), placed.order().id(), NOW.plusSeconds(3601), "over-ttl"));
        });

        tests.test("expired_and_terminal_orders_cannot_be_rescheduled", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(5);
            Order expired = Order.reserved(OrderId.random(), new RequestId("rs-expired-seed"), SKU,
                    new Quantity(1), NOW.minusSeconds(7200), NOW.minusSeconds(1));
            fixture.inventory.tryReserve(SKU, new Quantity(1), NOW);
            fixture.orders.insert(expired);
            expectThrows(ValidationException.class, () -> fixture.reschedules.reschedule(OPERATOR,
                    new RequestId("rs-expired"), expired.id(), NOW.plusSeconds(600), "revive"));

            var live = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "rs-term-place", SKU.value(), 1));
            fixture.transitions.transition(OPERATOR, new RequestId("rs-cancel"), live.order().id(),
                    OrderStatus.CANCELLED, "gone");
            expectThrows(OrderStateConflictException.class, () -> fixture.reschedules.reschedule(OPERATOR,
                    new RequestId("rs-after-cancel"), live.order().id(), NOW.plusSeconds(600), "late"));
        });

        tests.test("cancel_wins_over_reschedule_when_status_already_terminal", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(5);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "rs-race-place", SKU.value(), 1));
            fixture.transitions.transition(OPERATOR, new RequestId("rs-race-cancel"), placed.order().id(),
                    OrderStatus.CANCELLED, "expire-path");
            expectThrows(OrderStateConflictException.class, () -> fixture.reschedules.reschedule(OPERATOR,
                    new RequestId("rs-race-extend"), placed.order().id(), NOW.plusSeconds(900), "too-late"));
            expectEquals(OrderStatus.CANCELLED, fixture.orders.findById(placed.order().id()).orElseThrow().status());
            expectEquals(0, fixture.rescheduleOperations.size());
        });

        tests.test("same_key_different_expires_at_is_idempotency_conflict", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(5);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "rs-idem-place", SKU.value(), 1));
            Instant first = NOW.plusSeconds(1200);
            fixture.reschedules.reschedule(OPERATOR, new RequestId("rs-idem"), placed.order().id(),
                    first, "once");
            expectThrows(IdempotencyConflictException.class, () -> fixture.reschedules.reschedule(OPERATOR,
                    new RequestId("rs-idem"), placed.order().id(), NOW.plusSeconds(1500), "once"));
            var replay = fixture.reschedules.reschedule(OPERATOR, new RequestId("rs-idem"),
                    placed.order().id(), first, "once");
            expectEquals(true, replay.replayed());
            expectEquals(first, replay.order().expiresAt());
        });
    }
}

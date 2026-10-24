package concept;

import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.ResourceNotFoundException;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.OrderStateConflictException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;
import jtest.Concept;
import jtest.JTestRegistry;

import static concept.ConceptFixture.VIEWER;
import static concept.ConceptFixture.OPERATOR;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class OrderLifecycleConcept implements Concept {
    @Override
    public String name() {
        return "OrderLifecycle";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("cancellation_releases_reservation_and_replays_safely", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Order order = fixture.service.place(fixture.command("place-1", 3)).order();
            expectEquals(7, fixture.available());
            expectEquals(3, fixture.reserved());

            var cancelled = fixture.transitions.transition(ConceptFixture.OPERATOR, new RequestId("cancel-1"),
                    order.id(), OrderStatus.CANCELLED, "customer requested cancellation");
            var replay = fixture.transitions.transition(ConceptFixture.OPERATOR, new RequestId("cancel-1"),
                    order.id(), OrderStatus.CANCELLED, "customer requested cancellation");

            expectEquals(OrderStatus.CANCELLED, cancelled.order().status());
            expectTrue(replay.replayed());
            expectEquals(10, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(1L, cancelled.order().version());
            expectEquals(4, fixture.audit.size());
        });

        tests.test("confirmation_consumes_reservation_without_refilling_available", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Order order = fixture.service.place(fixture.command("place-1", 4)).order();

            var confirmed = fixture.transitions.transition(ConceptFixture.OPERATOR, new RequestId("confirm-1"),
                    order.id(), OrderStatus.CONFIRMED, "payment confirmed");
            var replay = fixture.transitions.transition(ConceptFixture.OPERATOR, new RequestId("confirm-1"),
                    order.id(), OrderStatus.CONFIRMED, "payment confirmed");

            expectEquals(OrderStatus.CONFIRMED, confirmed.order().status());
            expectTrue(replay.replayed());
            expectEquals(6, fixture.available());
            expectEquals(0, fixture.reserved());
        });

        tests.test("confirmed_order_cannot_be_cancelled", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Order order = fixture.service.place(fixture.command("place-1", 2)).order();
            fixture.transitions.transition(ConceptFixture.OPERATOR, new RequestId("confirm-1"),
                    order.id(), OrderStatus.CONFIRMED, "payment confirmed");

            expectThrows(OrderStateConflictException.class, () -> fixture.transitions.transition(
                    ConceptFixture.OPERATOR, new RequestId("cancel-1"), order.id(),
                    OrderStatus.CANCELLED, "late cancellation"));

            expectEquals(8, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(1, fixture.orderOperations.size());
        });

        tests.test("receipt_key_is_bound_to_order_target_and_reason", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(20);
            Order first = fixture.service.place(fixture.command("place-first", 2)).order();
            Order second = fixture.service.place(fixture.command("place-second", 3)).order();
            fixture.transitions.transition(OPERATOR, new RequestId("shared-transition"), first.id(),
                    OrderStatus.CANCELLED, "first cancellation");

            expectThrows(IdempotencyConflictException.class, () -> fixture.transitions.transition(
                    OPERATOR, new RequestId("shared-transition"), first.id(), OrderStatus.CONFIRMED, "first cancellation"));
            expectThrows(IdempotencyConflictException.class, () -> fixture.transitions.transition(
                    OPERATOR, new RequestId("shared-transition"), first.id(), OrderStatus.CANCELLED, "changed reason"));
            expectThrows(IdempotencyConflictException.class, () -> fixture.transitions.transition(
                    OPERATOR, new RequestId("shared-transition"), second.id(), OrderStatus.CANCELLED, "first cancellation"));

            expectEquals(OrderStatus.CANCELLED, fixture.orders.findById(first.id()).orElseThrow().status());
            expectEquals(OrderStatus.RESERVED, fixture.orders.findById(second.id()).orElseThrow().status());
            expectEquals(17, fixture.available());
            expectEquals(3, fixture.reserved());
            expectEquals(1, fixture.orderOperations.size());
        });

        tests.test("terminal_and_unknown_order_failures_do_not_create_receipts_or_audits", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Order order = fixture.service.place(fixture.command("place-terminal", 2)).order();
            fixture.transitions.transition(OPERATOR, new RequestId("cancel-terminal"), order.id(),
                    OrderStatus.CANCELLED, "terminal seed");
            int auditBefore = fixture.audit.size();

            expectThrows(OrderStateConflictException.class, () -> fixture.transitions.transition(
                    OPERATOR, new RequestId("confirm-terminal"), order.id(), OrderStatus.CONFIRMED, "too late"));
            expectThrows(ResourceNotFoundException.class, () -> fixture.transitions.transition(
                    OPERATOR, new RequestId("unknown-order"), OrderId.random(), OrderStatus.CANCELLED, "missing"));

            expectEquals(auditBefore, fixture.audit.size());
            expectEquals(1, fixture.orderOperations.size());
            expectEquals(10, fixture.available());
            expectEquals(0, fixture.reserved());
        });

        tests.test("transition_reason_and_target_validation_happen_before_lock_or_transaction", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Order order = fixture.service.place(fixture.command("place-validation", 1)).order();
            int locksBefore = fixture.locks.acquired;
            int transactionsBefore = fixture.transactions.writes();
            int reasonIndex = 0;
            for (String reason : new String[]{null, "", " ", " leading", "trailing ", "r".repeat(201)}) {
                String key = "invalid-transition-" + reasonIndex++;
                expectThrows(ValidationException.class, () -> fixture.transitions.transition(
                        OPERATOR, new RequestId(key), order.id(), OrderStatus.CONFIRMED, reason));
            }
            expectThrows(ValidationException.class, () -> fixture.transitions.transition(
                    OPERATOR, new RequestId("reserved-target"), order.id(), OrderStatus.RESERVED, "invalid target"));

            expectEquals(locksBefore, fixture.locks.acquired);
            expectEquals(transactionsBefore, fixture.transactions.writes());
            expectEquals(0, fixture.orderOperations.size());
        });

        tests.test("viewer_cannot_mutate_order", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);

            expectThrows(AccessDeniedException.class,
                    () -> fixture.service.place(fixture.command(VIEWER, "place-v", 1)));

            expectEquals(10, fixture.available());
            expectEquals(0, fixture.orders.size());
            expectEquals(0, fixture.transactions.writes());
        });
    }
}

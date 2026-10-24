package concept;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.domain.exception.OrderStateConflictException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import jtest.Concept;
import jtest.JTestRegistry;

import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

/** ADR 002: partial confirm shrinks RESERVED quantity; full confirm terminates. */
public final class PartialConfirmConcept implements Concept {
    @Override
    public String name() {
        return "PartialConfirm";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("partial_confirm_keeps_reserved_and_shrinks_quantity", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "pc-place", SKU.value(), 5));
            expectEquals(5, fixture.reserved());

            var partial = fixture.transitions.confirm(OPERATOR, new RequestId("pc-partial"),
                    placed.order().id(), new Quantity(2), "ship-two");
            expectEquals(false, partial.replayed());
            expectEquals(OrderStatus.RESERVED, partial.order().status());
            expectEquals(3, partial.order().quantity().value());
            expectEquals(3, fixture.reserved());
            expectEquals(5, fixture.available());
            expectTrue(fixture.audit.snapshot().contains("ORDER_PARTIAL_CONFIRMED"));
            expectTrue(fixture.audit.snapshot().contains("STOCK_CONFIRMED"));
        });

        tests.test("confirm_all_remaining_reaches_confirmed", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "pc-full-place", SKU.value(), 5));
            var confirmed = fixture.transitions.confirm(OPERATOR, new RequestId("pc-full"),
                    placed.order().id(), new Quantity(5), "ship-all");
            expectEquals(OrderStatus.CONFIRMED, confirmed.order().status());
            expectEquals(0, fixture.reserved());
            expectEquals(5, fixture.available());
        });

        tests.test("null_quantity_confirms_all_remaining", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(8);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "pc-null-place", SKU.value(), 4));
            fixture.transitions.confirm(OPERATOR, new RequestId("pc-half"), placed.order().id(),
                    new Quantity(1), "one");
            var rest = fixture.transitions.confirm(OPERATOR, new RequestId("pc-rest"),
                    placed.order().id(), null, "rest");
            expectEquals(OrderStatus.CONFIRMED, rest.order().status());
            expectEquals(0, fixture.reserved());
        });

        tests.test("invalid_quantities_and_terminal_orders_are_rejected", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(5);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "pc-bad-place", SKU.value(), 3));
            expectThrows(ValidationException.class, () -> fixture.transitions.confirm(OPERATOR,
                    new RequestId("pc-zero"), placed.order().id(), new Quantity(0), "bad"));
            expectThrows(ValidationException.class, () -> fixture.transitions.confirm(OPERATOR,
                    new RequestId("pc-over"), placed.order().id(), new Quantity(4), "bad"));
            fixture.transitions.transition(OPERATOR, new RequestId("pc-cancel"), placed.order().id(),
                    OrderStatus.CANCELLED, "gone");
            expectThrows(OrderStateConflictException.class, () -> fixture.transitions.confirm(OPERATOR,
                    new RequestId("pc-after"), placed.order().id(), new Quantity(1), "late"));
        });

        tests.test("same_key_different_quantity_is_idempotency_conflict", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            var placed = fixture.service.place(PlaceOrderCommand.of(OPERATOR, "pc-idem-place", SKU.value(), 5));
            fixture.transitions.confirm(OPERATOR, new RequestId("pc-idem"), placed.order().id(),
                    new Quantity(2), "two");
            expectThrows(IdempotencyConflictException.class, () -> fixture.transitions.confirm(OPERATOR,
                    new RequestId("pc-idem"), placed.order().id(), new Quantity(3), "two"));
            var replay = fixture.transitions.confirm(OPERATOR, new RequestId("pc-idem"),
                    placed.order().id(), new Quantity(2), "two");
            expectEquals(true, replay.replayed());
            expectEquals(3, replay.order().quantity().value());
        });
    }
}

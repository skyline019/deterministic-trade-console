package concept;

import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.InsufficientStockException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.result.PlaceOrderResult;
import com.acme.trade.domain.model.Inventory;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.StockLevel;
import jtest.Concept;
import jtest.JTestRegistry;

import static concept.ConceptFixture.NOW;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectFalse;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class OrderPlacementConcept implements Concept {
    @Override
    public String name() {
        return "OrderPlacement";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("reserves_with_deterministic_identity_and_time", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);

            PlaceOrderResult result = fixture.service.place(fixture.command("request-1", 3));

            expectFalse(result.replayed());
            expectEquals("00000000-0000-0000-0000-000000000001", result.order().id().toString());
            expectEquals(NOW, result.order().createdAt());
            expectEquals(7, fixture.available());
            expectEquals(1, fixture.orders.size());
            expectEquals(1, fixture.transactions.writes());
            expectEquals(1, fixture.locks.released);
        });

        tests.test("same_key_and_payload_replays_without_double_deduction", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            PlaceOrderResult first = fixture.service.place(fixture.command("request-1", 3));

            PlaceOrderResult retry = fixture.service.place(fixture.command("request-1", 3));

            expectTrue(retry.replayed());
            expectEquals(first.order(), retry.order());
            expectEquals(7, fixture.available());
            expectEquals(1, fixture.orders.size());
        });

        tests.test("same_key_with_another_payload_is_rejected", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            fixture.service.place(fixture.command("request-1", 2));

            expectThrows(IdempotencyConflictException.class,
                    () -> fixture.service.place(fixture.command("request-1", 4)));

            expectEquals(8, fixture.available());
            expectEquals(1, fixture.orders.size());
        });

        tests.test("insufficient_stock_creates_no_order", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(2);

            expectThrows(InsufficientStockException.class,
                    () -> fixture.service.place(fixture.command("request-1", 3)));

            expectEquals(2, fixture.available());
            expectEquals(0, fixture.orders.size());
            expectEquals(1, fixture.locks.released);
        });

        tests.test("busy_request_lock_has_no_database_side_effect", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            fixture.locks.available = false;

            expectThrows(RequestInProgressException.class,
                    () -> fixture.service.place(fixture.command("request-1", 1)));

            expectEquals(10, fixture.available());
            expectEquals(0, fixture.orders.size());
            expectEquals(0, fixture.transactions.writes());
        });

        tests.test("domain_stock_transition_is_immutable", () -> {
            Inventory original = new Inventory(SKU, new StockLevel(10), 4, NOW);

            Inventory reserved = original.reserve(new Quantity(3), NOW.plusSeconds(1));

            expectEquals(10, original.available().value());
            expectEquals(4, original.version());
            expectEquals(7, reserved.available().value());
            expectEquals(5, reserved.version());
            expectEquals(NOW.plusSeconds(1), reserved.updatedAt());
        });
    }
}

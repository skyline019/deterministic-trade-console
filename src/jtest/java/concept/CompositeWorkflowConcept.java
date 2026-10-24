package concept;

import com.acme.trade.application.exception.InsufficientStockException;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import jtest.Concept;
import jtest.JTestRegistry;

import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class CompositeWorkflowConcept implements Concept {
    @Override
    public String name() {
        return "CompositeWorkflows";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("same_key_is_safe_across_independent_order_inventory_and_transition_namespaces", () -> {
            ConceptFixture fixture = new ConceptFixture();
            RequestId shared = new RequestId("shared-across-commands");
            fixture.inventoryService.create(OPERATOR, new RequestId("namespace-setup"), SKU,
                    new StockLevel(10), "namespace seed");
            var order = fixture.service.place(fixture.command("shared-across-commands", 2)).order();
            fixture.inventoryService.adjust(OPERATOR, shared, SKU, -1, "independent inventory receipt");
            var cancelled = fixture.transitions.transition(OPERATOR, shared, order.id(),
                    OrderStatus.CANCELLED, "independent transition receipt");

            expectEquals(OrderStatus.CANCELLED, cancelled.order().status());
            expectEquals(9, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(2, fixture.inventoryOperations.size());
            expectEquals(1, fixture.orderOperations.size());
            expectEquals(1, fixture.orders.size());
        });

        tests.test("place_replay_after_cancellation_returns_current_order_without_reserving_again", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            var placed = fixture.service.place(fixture.command("replay-terminal", 3)).order();
            fixture.transitions.transition(OPERATOR, new RequestId("cancel-before-replay"), placed.id(),
                    OrderStatus.CANCELLED, "cancel before client retry");

            var replay = fixture.service.place(fixture.command("replay-terminal", 3));

            expectTrue(replay.replayed());
            expectEquals(OrderStatus.CANCELLED, replay.order().status());
            expectEquals(placed.id(), replay.order().id());
            expectEquals(10, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(1, fixture.orders.size());
        });

        tests.test("one_sku_failure_does_not_block_or_modify_another_sku", () -> {
            ConceptFixture fixture = new ConceptFixture();
            Sku other = Sku.of("SKU-OTHER");
            fixture.inventoryService.create(OPERATOR, new RequestId("create-primary"), SKU, new StockLevel(5), "primary");
            fixture.inventoryService.create(OPERATOR, new RequestId("create-other"), other, new StockLevel(4), "other");
            var primary = fixture.service.place(fixture.command("primary-all", 5)).order();

            expectThrows(InsufficientStockException.class,
                    () -> fixture.service.place(com.acme.trade.application.command.PlaceOrderCommand.of(
                            OPERATOR, "other-too-many", other.value(), 5)));

            expectEquals(4, fixture.inventory.find(other).orElseThrow().available().value());
            expectEquals(0, fixture.inventory.find(other).orElseThrow().reserved().value());
            fixture.transitions.transition(OPERATOR, new RequestId("cancel-primary"), primary.id(),
                    OrderStatus.CANCELLED, "release primary");
            var otherOrder = fixture.service.place(com.acme.trade.application.command.PlaceOrderCommand.of(
                    OPERATOR, "other-allowed", other.value(), 2)).order();

            expectEquals(OrderStatus.RESERVED, otherOrder.status());
            expectEquals(5, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(2, fixture.inventory.find(other).orElseThrow().available().value());
            expectEquals(2, fixture.inventory.find(other).orElseThrow().reserved().value());
        });

        tests.test("create_adjust_reserve_cancel_reserve_confirm_is_one_auditable_composite", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.inventoryService.create(OPERATOR, new RequestId("composite-create"), SKU,
                    new StockLevel(10), "composite seed");
            fixture.inventoryService.adjust(OPERATOR, new RequestId("composite-adjust"), SKU, -2, "cycle count");
            var first = fixture.service.place(fixture.command("composite-first", 3)).order();
            fixture.transitions.transition(OPERATOR, new RequestId("composite-cancel"), first.id(),
                    OrderStatus.CANCELLED, "first cancelled");
            var second = fixture.service.place(fixture.command("composite-second", 4)).order();
            fixture.transitions.transition(OPERATOR, new RequestId("composite-confirm"), second.id(),
                    OrderStatus.CONFIRMED, "second confirmed");

            expectEquals(4, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(OrderStatus.CANCELLED, fixture.orders.findById(first.id()).orElseThrow().status());
            expectEquals(OrderStatus.CONFIRMED, fixture.orders.findById(second.id()).orElseThrow().status());
            expectEquals(2, fixture.orders.size());
            expectEquals(2, fixture.inventoryOperations.size());
            expectEquals(2, fixture.orderOperations.size());
            expectEquals(10, fixture.audit.size());
            expectEquals(2, fixture.orders.findPage(OrderPageQuery.firstPage(10), 11).size());
        });
    }
}

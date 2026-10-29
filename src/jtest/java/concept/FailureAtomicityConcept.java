package concept;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.DuplicateRequestException;
import com.acme.trade.application.exception.StockAdjustmentRejectedException;
import com.acme.trade.application.port.ExpiredReservationScan;
import com.acme.trade.application.port.LockManager;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.application.service.PlaceOrderService;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import jtest.Concept;
import jtest.JTestRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static concept.ConceptFixture.CLOCK;
import static concept.ConceptFixture.NOW;
import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectFalse;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class FailureAtomicityConcept implements Concept {
    @Override
    public String name() {
        return "FailureAtomicity";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("order_insert_failure_rolls_back_reservation_and_releases_lock", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            fixture.orders.insertFailure = new IllegalStateException("injected order insert failure");

            expectThrows(IllegalStateException.class, () -> fixture.service.place(fixture.command("failure-insert", 3)));

            expectEquals(10, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(0, fixture.orders.size());
            expectEquals(0, fixture.audit.size());
            expectEquals(fixture.locks.acquired, fixture.locks.released);
        });

        tests.test("audit_failure_rolls_back_order_and_both_ledgers", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            fixture.audit.failure = new IllegalStateException("injected audit failure");

            expectThrows(IllegalStateException.class, () -> fixture.service.place(fixture.command("failure-audit", 4)));

            expectEquals(10, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(0, fixture.orders.size());
            expectEquals(0, fixture.audit.size());
        });

        tests.test("inventory_receipt_failure_rolls_back_the_adjustment", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            fixture.inventoryOperations.insertFailure = new IllegalStateException("injected receipt failure");

            expectThrows(IllegalStateException.class, () -> fixture.inventoryService.adjust(
                    OPERATOR, new RequestId("adjust-failure"), SKU, -3, "injected failure"));

            expectEquals(10, fixture.available());
            expectEquals(0, fixture.inventoryOperations.size());
            expectEquals(0, fixture.audit.size());
        });

        tests.test("transition_receipt_failure_happens_before_any_mutable_effect", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Order order = fixture.service.place(fixture.command("place-before-failure", 3)).order();
            fixture.orderOperations.insertFailure = new IllegalStateException("injected transition receipt failure");

            expectThrows(IllegalStateException.class, () -> fixture.transitions.transition(
                    OPERATOR, new RequestId("transition-failure"), order.id(), OrderStatus.CONFIRMED, "failure"));

            expectEquals(OrderStatus.RESERVED, fixture.orders.findById(order.id()).orElseThrow().status());
            expectEquals(7, fixture.available());
            expectEquals(3, fixture.reserved());
            expectEquals(0, fixture.orderOperations.size());
            expectEquals(2, fixture.audit.size());
        });

        tests.test("ledger_conflict_rolls_back_status_and_receipt_to_precall_snapshot", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            Order order = fixture.service.place(fixture.command("place-before-corruption", 3)).order();
            fixture.inventory.replaceStock(SKU, new StockLevel(10), NOW);

            expectThrows(StockAdjustmentRejectedException.class, () -> fixture.transitions.transition(
                    OPERATOR, new RequestId("cancel-corrupted"), order.id(), OrderStatus.CANCELLED, "corrupted ledger"));

            expectEquals(OrderStatus.RESERVED, fixture.orders.findById(order.id()).orElseThrow().status());
            expectEquals(10, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(0, fixture.orderOperations.size());
            expectEquals(2, fixture.audit.size());
        });

        tests.test("unique_key_race_replays_winner_after_loser_rollback", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            fixture.inventory.tryReserve(SKU, new Quantity(3), NOW);
            Order winner = Order.reserved(OrderId.random(), new RequestId("race-key"), SKU, new Quantity(3),
                    NOW, NOW.plusSeconds(3600));
            fixture.orders.insert(winner);
            AtomicInteger reads = new AtomicInteger();
            OrderRepository racingOrders = new OrderRepository() {
                @Override
                public Optional<Order> findByRequestId(RequestId requestId) {
                    return reads.getAndIncrement() == 0 ? Optional.empty() : fixture.orders.findByRequestId(requestId);
                }

                @Override
                public Optional<Order> findById(OrderId orderId) {
                    return fixture.orders.findById(orderId);
                }

                @Override
                public List<Order> findPage(OrderPageQuery query, int fetchLimit) {
                    return fixture.orders.findPage(query, fetchLimit);
                }

                @Override
                public ExpiredReservationScan findExpiredReserved(Instant now, int limit) {
                    return fixture.orders.findExpiredReserved(now, limit);
                }

                @Override
                public boolean transitionStatus(OrderId orderId, OrderStatus expected, OrderStatus target, Instant at) {
                    return fixture.orders.transitionStatus(orderId, expected, target, at);
                }

                @Override
                public boolean reduceReservedQuantity(OrderId orderId, int expectedQuantity, int newQuantity,
                                                      Instant at) {
                    return fixture.orders.reduceReservedQuantity(orderId, expectedQuantity, newQuantity, at);
                }

                @Override
                public boolean updateExpiresAt(OrderId orderId, Instant expectedExpiresAt, Instant newExpiresAt,
                                               Instant at) {
                    return fixture.orders.updateExpiresAt(orderId, expectedExpiresAt, newExpiresAt, at);
                }

                @Override
                public void insert(Order order) {
                    fixture.orders.insert(order);
                }
            };
            LockManager noLock = (key, ttl) -> Optional.of(() -> { });
            PlaceOrderService racing = new PlaceOrderService(fixture.inventory, racingOrders, noLock,
                    fixture.transactions, OrderId::random, CLOCK, Duration.ofSeconds(10), Duration.ofHours(1),
                    fixture.audit);

            var result = racing.place(PlaceOrderCommand.of(OPERATOR, "race-key", SKU.value(), 3));

            expectTrue(result.replayed());
            expectEquals(winner.id(), result.order().id());
            expectEquals(7, fixture.available());
            expectEquals(3, fixture.reserved());
            expectEquals(1, fixture.orders.size());
        });

        tests.test("duplicate_without_a_committed_winner_does_not_hide_the_infrastructure_failure", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            fixture.orders.insertFailure = new DuplicateRequestException("missing-winner", null);

            expectThrows(DuplicateRequestException.class,
                    () -> fixture.service.place(fixture.command("missing-winner", 2)));

            expectEquals(10, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(0, fixture.orders.size());
            expectFalse(fixture.audit.size() > 0);
        });
    }
}

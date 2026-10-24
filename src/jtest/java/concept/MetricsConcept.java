package concept;

import com.acme.trade.adapter.observability.MeteredPlaceOrderUseCase;
import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.InsufficientStockException;
import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.result.PlaceOrderResult;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jtest.Concept;
import jtest.JTestRegistry;

import java.util.concurrent.atomic.AtomicInteger;

import static concept.ConceptFixture.NOW;
import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;

public final class MetricsConcept implements Concept {
    @Override
    public String name() {
        return "BoundedMetrics";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("meter_registry_is_constant_after_repeated_registration_safe_calls", () -> {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            Order order = Order.reserved(OrderId.random(), new RequestId("metric-order"), SKU, new Quantity(1),
                    NOW, NOW.plusSeconds(3600));
            AtomicInteger calls = new AtomicInteger();
            MeteredPlaceOrderUseCase metered = new MeteredPlaceOrderUseCase(command -> {
                int call = calls.incrementAndGet();
                return call % 2 == 0 ? PlaceOrderResult.replayed(order) : PlaceOrderResult.created(order);
            }, registry);
            int initialMeters = registry.getMeters().size();

            for (int iteration = 0; iteration < 1_000; iteration++) {
                metered.place(PlaceOrderCommand.of(OPERATOR, "unique-" + iteration, SKU.value(), 1));
            }

            expectEquals(18, initialMeters);
            expectEquals(initialMeters, registry.getMeters().size());
            expectEquals(500.0, registry.get("trade.order.requests").tag("outcome", "CREATED").counter().count());
            expectEquals(500.0, registry.get("trade.order.requests").tag("outcome", "REPLAYED").counter().count());
            expectEquals(500L, registry.get("trade.order.duration").tag("outcome", "CREATED").timer().count());
            expectEquals(2, registry.getMeters().stream().map(meter -> meter.getId().getName()).distinct().count());
        });

        tests.test("every_failure_outcome_is_classified_without_request_id_cardinality", () -> {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MeteredPlaceOrderUseCase metered = new MeteredPlaceOrderUseCase(command -> {
                String key = command.requestId().value();
                if (key.equals("stock")) {
                    throw new InsufficientStockException(SKU);
                }
                if (key.equals("denied")) {
                    throw new AccessDeniedException("ORDER_WRITE");
                }
                if (key.equals("conflict")) {
                    throw new IdempotencyConflictException(key);
                }
                if (key.equals("busy")) {
                    throw new RequestInProgressException(key);
                }
                if (key.equals("validation")) {
                    throw new ValidationException("bad request");
                }
                if (key.equals("lock")) {
                    throw new LockUnavailableException("redis unavailable");
                }
                throw new IllegalStateException("injected");
            }, registry);

            expectThrows(InsufficientStockException.class, () -> metered.place(command("stock")));
            expectThrows(AccessDeniedException.class, () -> metered.place(command("denied")));
            expectThrows(IdempotencyConflictException.class, () -> metered.place(command("conflict")));
            expectThrows(RequestInProgressException.class, () -> metered.place(command("busy")));
            expectThrows(ValidationException.class, () -> metered.place(command("validation")));
            expectThrows(LockUnavailableException.class, () -> metered.place(command("lock")));
            expectThrows(IllegalStateException.class, () -> metered.place(command("error")));

            for (String outcome : new String[]{"OUT_OF_STOCK", "DENIED", "IDEMPOTENCY_CONFLICT",
                    "REQUEST_IN_PROGRESS", "VALIDATION_ERROR", "LOCK_UNAVAILABLE", "ERROR"}) {
                expectEquals(1.0, registry.get("trade.order.requests").tag("outcome", outcome).counter().count());
                expectEquals(1L, registry.get("trade.order.duration").tag("outcome", outcome).timer().count());
            }
            expectEquals(18, registry.getMeters().size());
        });
    }

    private static PlaceOrderCommand command(String key) {
        return PlaceOrderCommand.of(OPERATOR, key, SKU.value(), 1);
    }
}

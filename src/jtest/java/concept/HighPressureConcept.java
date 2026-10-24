package concept;

import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.InsufficientStockException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;
import jtest.Concept;
import jtest.JTestRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class HighPressureConcept implements Concept {
    @Override
    public String name() {
        return "HighPressure";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("four_hundred_unique_requests_never_oversell_and_all_locks_close", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(125);
            fixture.transactions.rollbackEnabled = false;
            List<Callable<String>> tasks = new ArrayList<>();
            for (int id = 0; id < 400; id++) {
                int request = id;
                tasks.add(() -> {
                    try {
                        fixture.service.place(fixture.command("pressure-unique-" + request, 1));
                        return "CREATED";
                    } catch (InsufficientStockException exception) {
                        return "OUT_OF_STOCK";
                    }
                });
            }

            List<String> outcomes = run(32, tasks);

            expectEquals(125, count(outcomes, "CREATED"));
            expectEquals(275, count(outcomes, "OUT_OF_STOCK"));
            expectEquals(0, fixture.available());
            expectEquals(125, fixture.reserved());
            expectEquals(125, fixture.orders.size());
            expectEquals(250, fixture.audit.size());
            expectEquals(fixture.locks.acquired, fixture.locks.released);
        });

        tests.test("same_key_storm_has_one_effect_and_explicit_busy_replay_outcomes", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(10);
            fixture.transactions.rollbackEnabled = false;
            List<Callable<String>> tasks = new ArrayList<>();
            for (int id = 0; id < 300; id++) {
                tasks.add(() -> {
                    try {
                        var result = fixture.service.place(fixture.command("pressure-same-key", 3));
                        return result.replayed() ? "REPLAYED" : "CREATED";
                    } catch (RequestInProgressException exception) {
                        return "BUSY";
                    }
                });
            }

            List<String> outcomes = run(24, tasks);

            expectEquals(1, count(outcomes, "CREATED"));
            expectEquals(299, count(outcomes, "REPLAYED") + count(outcomes, "BUSY"));
            expectEquals(7, fixture.available());
            expectEquals(3, fixture.reserved());
            expectEquals(1, fixture.orders.size());
            expectTrue(fixture.service.place(fixture.command("pressure-same-key", 3)).replayed());
            expectEquals(7, fixture.available());
            expectEquals(fixture.locks.acquired, fixture.locks.released);
        });

        tests.test("mixed_payload_storm_cannot_change_the_winning_request_fingerprint", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(100);
            fixture.transactions.rollbackEnabled = false;
            List<Callable<String>> tasks = new ArrayList<>();
            for (int id = 0; id < 200; id++) {
                int quantity = id % 2 + 1;
                tasks.add(() -> {
                    try {
                        var result = fixture.service.place(fixture.command("pressure-mixed", quantity));
                        return result.replayed() ? "REPLAYED" : "CREATED";
                    } catch (RequestInProgressException exception) {
                        return "BUSY";
                    } catch (IdempotencyConflictException exception) {
                        return "CONFLICT";
                    }
                });
            }

            List<String> outcomes = run(24, tasks);
            Order winner = fixture.orders.findPage(com.acme.trade.application.query.OrderPageQuery.firstPage(1), 2).get(0);

            expectEquals(1, count(outcomes, "CREATED"));
            expectEquals(1, fixture.orders.size());
            int oppositeQuantity = winner.quantity().value() == 1 ? 2 : 1;
            expectThrows(IdempotencyConflictException.class,
                    () -> fixture.service.place(fixture.command("pressure-mixed", oppositeQuantity)));
            expectEquals(100 - winner.quantity().value(), fixture.available());
            expectEquals(winner.quantity().value(), fixture.reserved());
            expectEquals(fixture.locks.acquired, fixture.locks.released);
        });

        tests.test("one_hundred_orders_confirm_and_cancel_concurrently_without_ledger_drift", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.seed(200);
            fixture.transactions.rollbackEnabled = false;
            List<OrderId> ids = Collections.synchronizedList(new ArrayList<>());
            List<Callable<String>> placements = new ArrayList<>();
            for (int id = 0; id < 100; id++) {
                int request = id;
                placements.add(() -> {
                    Order order = fixture.service.place(fixture.command("pressure-life-" + request, 1)).order();
                    ids.add(order.id());
                    return "CREATED";
                });
            }
            run(20, placements);
            List<OrderId> snapshot = List.copyOf(ids);
            List<Callable<String>> transitions = new ArrayList<>();
            for (int index = 0; index < snapshot.size(); index++) {
                OrderId id = snapshot.get(index);
                OrderStatus target = index % 2 == 0 ? OrderStatus.CONFIRMED : OrderStatus.CANCELLED;
                transitions.add(() -> {
                    fixture.transitions.transition(OPERATOR, new RequestId("pressure-transition-" + id), id,
                            target, "high pressure lifecycle");
                    return target.name();
                });
            }

            List<String> outcomes = run(20, transitions);

            expectEquals(50, count(outcomes, "CONFIRMED"));
            expectEquals(50, count(outcomes, "CANCELLED"));
            expectEquals(150, fixture.available());
            expectEquals(0, fixture.reserved());
            expectEquals(100, fixture.orderOperations.size());
            expectEquals(400, fixture.audit.size());
            expectEquals(fixture.locks.acquired, fixture.locks.released);
        });
    }

    private static List<String> run(int threads, List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(45, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("high pressure executor did not terminate");
            }
        }
    }

    private static int count(List<String> values, String expected) {
        return (int) values.stream().filter(expected::equals).count();
    }
}

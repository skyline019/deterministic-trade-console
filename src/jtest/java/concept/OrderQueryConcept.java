package concept;

import com.acme.trade.application.exception.ResourceNotFoundException;
import com.acme.trade.application.query.OrderPage;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.application.service.OrderQueryService;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderCursor;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import jtest.Concept;
import jtest.JTestRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static concept.ConceptFixture.NOW;
import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.VIEWER;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectFalse;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class OrderQueryConcept implements Concept {
    @Override
    public String name() {
        return "OrderQueries";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("keyset_pages_cover_every_row_once_including_timestamp_ties", () -> {
            ConceptFixture fixture = new ConceptFixture();
            Instant tied = NOW.plusSeconds(5);
            for (int id = 1; id <= 9; id++) {
                fixture.orders.insert(order(id, OrderStatus.RESERVED, "SKU-A", id < 5 ? tied : NOW.plusSeconds(id)));
            }
            OrderQueryService queries = new OrderQueryService(fixture.orders, fixture.transactions);
            List<String> visited = new ArrayList<>();
            Optional<OrderCursor> cursor = Optional.empty();
            int pages = 0;
            do {
                OrderPage page = queries.list(VIEWER, new OrderPageQuery(Optional.empty(), Optional.empty(), cursor, 4));
                page.items().forEach(order -> visited.add(order.id().toString()));
                cursor = page.nextCursor();
                pages++;
            } while (cursor.isPresent());

            expectEquals(3, pages);
            expectEquals(9, visited.size());
            expectEquals(9, new HashSet<>(visited).size());
            List<String> sorted = new ArrayList<>(visited);
            sorted.sort((left, right) -> {
                Order a = fixture.orders.findById(OrderId.of(left)).orElseThrow();
                Order b = fixture.orders.findById(OrderId.of(right)).orElseThrow();
                int time = b.createdAt().compareTo(a.createdAt());
                return time != 0 ? time : b.id().toString().compareTo(a.id().toString());
            });
            expectEquals(sorted, visited);
        });

        tests.test("combined_status_sku_cursor_filters_do_not_reset_the_window", () -> {
            ConceptFixture fixture = new ConceptFixture();
            for (int id = 1; id <= 8; id++) {
                fixture.orders.insert(order(id, (id % 2 == 0 || id == 5) ? OrderStatus.CONFIRMED : OrderStatus.CANCELLED,
                        id < 6 ? "SKU-A" : "SKU-B", NOW.plusSeconds(id)));
            }
            OrderQueryService queries = new OrderQueryService(fixture.orders, fixture.transactions);
            OrderPage first = queries.list(OPERATOR, new OrderPageQuery(Optional.of(OrderStatus.CONFIRMED),
                    Optional.of(Sku.of("SKU-A")), Optional.empty(), 2));
            expectEquals(2, first.items().size());
            expectTrue(first.nextCursor().isPresent());
            OrderPage second = queries.list(OPERATOR, new OrderPageQuery(Optional.of(OrderStatus.CONFIRMED),
                    Optional.of(Sku.of("SKU-A")), first.nextCursor(), 2));
            expectEquals(1, second.items().size());
            expectTrue(second.nextCursor().isEmpty());
            first.items().forEach(order -> {
                expectEquals(OrderStatus.CONFIRMED, order.status());
                expectEquals("SKU-A", order.sku().value());
            });
            expectFalse(first.items().stream().anyMatch(order -> second.items().contains(order)));
        });

        tests.test("exact_empty_and_large_pages_have_correct_next_cursor_semantics", () -> {
            ConceptFixture fixture = new ConceptFixture();
            OrderQueryService queries = new OrderQueryService(fixture.orders, fixture.transactions);
            expectTrue(queries.list(VIEWER, OrderPageQuery.firstPage(30)).nextCursor().isEmpty());
            fixture.orders.insert(order(1, OrderStatus.RESERVED, "SKU-A", NOW));
            fixture.orders.insert(order(2, OrderStatus.RESERVED, "SKU-A", NOW.plusSeconds(1)));
            OrderPage exact = queries.list(VIEWER, OrderPageQuery.firstPage(2));
            expectEquals(2, exact.items().size());
            expectTrue(exact.nextCursor().isEmpty());
            expectThrows(ValidationException.class, () -> OrderPageQuery.firstPage(0));
            expectThrows(ValidationException.class, () -> OrderPageQuery.firstPage(101));
            expectThrows(NullPointerException.class, () -> new OrderPageQuery(null, Optional.empty(), Optional.empty(), 10));
        });

        tests.test("read_permissions_and_not_found_paths_are_explicit", () -> {
            ConceptFixture fixture = new ConceptFixture();
            Order order = order(1, OrderStatus.RESERVED, "SKU-A", NOW);
            fixture.orders.insert(order);
            OrderQueryService queries = new OrderQueryService(fixture.orders, fixture.transactions);
            expectEquals(order.id(), queries.get(VIEWER, order.id()).id());
            expectThrows(ResourceNotFoundException.class, () -> queries.get(VIEWER, OrderId.random()));
            ActorWithoutRoles anonymous = new ActorWithoutRoles();
            expectThrows(AccessDeniedException.class, () -> queries.list(anonymous.actor(), OrderPageQuery.firstPage(10)));
            expectThrows(AccessDeniedException.class, () -> queries.get(anonymous.actor(), order.id()));
        });

        tests.test("two_hundred_fifty_rows_remain_stable_across_maximum_size_pages", () -> {
            ConceptFixture fixture = new ConceptFixture();
            for (int id = 1; id <= 250; id++) {
                fixture.orders.insert(order(id, OrderStatus.RESERVED, "SKU-A", NOW.plusSeconds(id % 17)));
            }
            OrderQueryService queries = new OrderQueryService(fixture.orders, fixture.transactions);
            Set<String> ids = new HashSet<>();
            Optional<OrderCursor> cursor = Optional.empty();
            int pages = 0;
            do {
                OrderPage page = queries.list(VIEWER, new OrderPageQuery(Optional.empty(), Optional.empty(), cursor, 100));
                expectTrue(page.items().size() <= 100);
                page.items().forEach(order -> expectTrue(ids.add(order.id().toString())));
                cursor = page.nextCursor();
                pages++;
            } while (cursor.isPresent());
            expectEquals(3, pages);
            expectEquals(250, ids.size());
        });
    }

    private static Order order(int sequence, OrderStatus status, String sku, Instant at) {
        Order order = Order.reserved(new OrderId(new UUID(0, sequence)), new RequestId("query-" + sequence),
                Sku.of(sku), new Quantity(1), at, at.plusSeconds(3600));
        return status == OrderStatus.RESERVED ? order : order.transitionTo(status, at);
    }

    private static final class ActorWithoutRoles {
        com.acme.trade.domain.security.Actor actor() {
            return new com.acme.trade.domain.security.Actor("anonymous", "anonymous", Set.of());
        }
    }
}

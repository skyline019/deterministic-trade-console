package concept;

import com.acme.trade.application.port.AuditRepository;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.application.result.DashboardStats;
import com.acme.trade.application.service.AuditService;
import com.acme.trade.application.service.DashboardService;
import com.acme.trade.application.service.OrderQueryService;
import com.acme.trade.domain.audit.AggregateRef;
import com.acme.trade.domain.audit.AggregateType;
import com.acme.trade.domain.audit.AuditEvent;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.StockLevel;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.Role;
import jtest.Concept;
import jtest.JTestRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static concept.ConceptFixture.CLOCK;
import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class ServiceCapabilityConcept implements Concept {
    @Override
    public String name() {
        return "ServiceCapabilities";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("role_by_write_operation_matrix_cannot_bypass_service_checks", () -> {
            for (Role role : Role.values()) {
                ConceptFixture fixture = new ConceptFixture();
                fixture.seed(10);
                var setup = fixture.service.place(fixture.command("setup-" + role, 2)).order();
                Actor actor = new Actor("id-" + role, role.name(), Set.of(role));
                boolean allowed = role == Role.ADMIN || role == Role.OPERATOR;

                if (allowed) {
                    fixture.inventoryService.adjust(actor, new RequestId("write-inventory-" + role), SKU, 1, "matrix");
                    fixture.transitions.transition(actor, new RequestId("write-order-" + role), setup.id(),
                            OrderStatus.CANCELLED, "matrix");
                    fixture.service.place(fixture.command(actor, "write-place-" + role, 1));
                    expectEquals(10, fixture.available());
                    expectEquals(1, fixture.reserved());
                } else {
                    expectThrows(AccessDeniedException.class, () -> fixture.inventoryService.adjust(
                            actor, new RequestId("denied-inventory-" + role), SKU, 1, "matrix"));
                    expectThrows(AccessDeniedException.class, () -> fixture.transitions.transition(
                            actor, new RequestId("denied-order-" + role), setup.id(), OrderStatus.CANCELLED, "matrix"));
                    expectThrows(AccessDeniedException.class,
                            () -> fixture.service.place(fixture.command(actor, "denied-place-" + role, 1)));
                    expectEquals(8, fixture.available());
                    expectEquals(2, fixture.reserved());
                }
            }
        });

        tests.test("read_dashboard_and_audit_capabilities_are_independent", () -> {
            for (Role role : Role.values()) {
                ConceptFixture fixture = new ConceptFixture();
                fixture.seed(5);
                var order = fixture.service.place(fixture.command("read-" + role, 1)).order();
                Actor actor = new Actor("id-" + role, role.name(), Set.of(role));
                OrderQueryService queries = new OrderQueryService(fixture.orders, fixture.transactions);
                DashboardService dashboard = new DashboardService(
                        () -> new DashboardStats(1, 4, 1, 1, 1, 0, 0), fixture.transactions);

                expectEquals(1, fixture.inventoryService.list(actor).size());
                expectEquals(order.id(), queries.get(actor, order.id()).id());
                expectEquals(1, queries.list(actor, OrderPageQuery.firstPage(10)).items().size());
                expectEquals(1L, dashboard.load(actor).totalOrders());
            }
        });

        tests.test("audit_service_enforces_auditor_role_limits_and_recent_ordering", () -> {
            ConceptFixture fixture = new ConceptFixture();
            InMemoryAudits repository = new InMemoryAudits();
            AuditService service = new AuditService(repository, UUID::randomUUID, CLOCK, fixture.transactions);
            AggregateRef aggregate = AggregateRef.of(AggregateType.ORDER, UUID.randomUUID().toString());
            service.record(OPERATOR, "ORDER_RESERVED", aggregate, new RequestId("audit-service-1"), java.util.Map.of("n", "1"));
            service.record(OPERATOR, "ORDER_CONFIRMED", aggregate, new RequestId("audit-service-2"), java.util.Map.of("n", "2"));

            for (Role role : Role.values()) {
                Actor actor = new Actor("id-" + role, role.name(), Set.of(role));
                if (role == Role.ADMIN || role == Role.AUDITOR) {
                    List<AuditEvent> recent = service.events(actor, aggregate, 1);
                    expectEquals(1, recent.size());
                    expectEquals("ORDER_CONFIRMED", recent.get(0).action());
                    expectTrue(service.verify(actor, aggregate).valid());
                } else {
                    expectThrows(AccessDeniedException.class, () -> service.events(actor, aggregate, 10));
                    expectThrows(AccessDeniedException.class, () -> service.verify(actor, aggregate));
                }
            }
            Actor admin = new Actor("admin", "admin", Set.of(Role.ADMIN));
            expectThrows(ValidationException.class, () -> service.events(admin, aggregate, 0));
            expectThrows(ValidationException.class, () -> service.events(admin, aggregate, 501));
        });
    }

    private static final class InMemoryAudits implements AuditRepository {
        private final List<AuditEvent> values = new ArrayList<>();

        @Override
        public Optional<AuditEvent> findLast(AggregateRef aggregate) {
            return values.stream().filter(event -> event.aggregate().equals(aggregate))
                    .max(Comparator.comparingLong(AuditEvent::sequence));
        }

        @Override
        public List<AuditEvent> findChain(AggregateRef aggregate) {
            return values.stream().filter(event -> event.aggregate().equals(aggregate))
                    .sorted(Comparator.comparingLong(AuditEvent::sequence)).toList();
        }

        @Override
        public List<AuditEvent> findRecent(AggregateRef aggregate, int limit) {
            List<AuditEvent> chain = findChain(aggregate);
            return chain.subList(Math.max(0, chain.size() - limit), chain.size());
        }

        @Override
        public List<AuditEvent> findFromSequence(AggregateRef aggregate, long fromSequenceInclusive) {
            return findChain(aggregate).stream()
                    .filter(event -> event.sequence() >= fromSequenceInclusive)
                    .toList();
        }

        @Override
        public Optional<AuditEvent> findByEventHash(AggregateRef aggregate, String eventHash) {
            return findChain(aggregate).stream()
                    .filter(event -> event.eventHash().equals(eventHash))
                    .findFirst();
        }

        @Override
        public void insert(AuditEvent event) {
            values.add(event);
        }
    }
}

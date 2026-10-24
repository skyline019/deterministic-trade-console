package concept;

import com.acme.trade.domain.audit.CanonicalJson;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.Inventory;
import com.acme.trade.domain.model.InventoryOperation;
import com.acme.trade.domain.model.InventoryOperationType;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderCursor;
import com.acme.trade.domain.model.OrderFingerprint;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderOperation;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import com.acme.trade.domain.security.AccessTokenHash;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.PasswordPolicy;
import com.acme.trade.domain.security.Permission;
import com.acme.trade.domain.security.Role;
import com.acme.trade.domain.security.UserAccount;
import jtest.Concept;
import jtest.JTestRegistry;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static concept.ConceptFixture.NOW;
import static concept.ConceptFixture.SKU;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectFalse;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class DomainBoundaryConcept implements Concept {
    @Override
    public String name() {
        return "DomainBoundaries";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("quantity_and_stock_boundary_matrix", () -> {
            expectEquals(1, new Quantity(1).value());
            expectEquals(Quantity.MAX, new Quantity(Quantity.MAX).value());
            for (int invalid : new int[]{-1, 0, Quantity.MAX + 1, Integer.MAX_VALUE}) {
                expectThrows(ValidationException.class, () -> new Quantity(invalid));
            }
            expectThrows(ValidationException.class, () -> Quantity.of(null));

            expectEquals(0, new StockLevel(StockLevel.MAX).apply(-StockLevel.MAX).value());
            expectEquals(StockLevel.MAX, new StockLevel(StockLevel.MAX).value());
            expectThrows(ValidationException.class, () -> new StockLevel(-1));
            expectThrows(ValidationException.class, () -> new StockLevel(StockLevel.MAX + 1));
            expectThrows(ValidationException.class, () -> new StockLevel(StockLevel.MAX).apply(1));
            expectThrows(ValidationException.class, () -> new StockLevel(0).apply(Integer.MIN_VALUE));
            expectThrows(ValidationException.class, () -> new StockLevel(0).subtract(new Quantity(1)));
        });

        tests.test("sku_and_request_id_accept_only_bounded_canonical_inputs", () -> {
            expectEquals("A", Sku.of("A").value());
            expectEquals(64, Sku.of("A".repeat(64)).value().length());
            for (String invalid : new String[]{"", " A", "A ", "A/B", "A:B", "A\nB", "A".repeat(65)}) {
                expectThrows(ValidationException.class, () -> Sku.of(invalid));
            }
            expectThrows(ValidationException.class, () -> Sku.of(null));

            expectEquals(128, new RequestId("r".repeat(128)).value().length());
            expectEquals("请求-1", new RequestId("请求-1").value());
            for (String invalid : new String[]{"", " ", " key", "key ", "a b", "a\nb", "a\tb", "r".repeat(129)}) {
                expectThrows(ValidationException.class, () -> new RequestId(invalid));
            }
            expectThrows(ValidationException.class, () -> new RequestId(null));
        });

        tests.test("cursor_roundtrip_preserves_nanoseconds_and_rejects_bad_inputs", () -> {
            OrderCursor cursor = new OrderCursor(Instant.parse("2026-01-02T03:04:05.123456789Z"), OrderId.random());
            expectEquals(cursor, OrderCursor.parse(cursor.encode()));
            for (String invalid : new String[]{"", "not-base64!", "YWJj", "MToyOjM", "bnVsbA=="}) {
                expectThrows(ValidationException.class, () -> OrderCursor.parse(invalid));
            }
            expectThrows(ValidationException.class, () -> OrderCursor.parse(null));
        });

        tests.test("all_order_source_target_pairs_follow_the_state_machine", () -> {
            for (OrderStatus source : OrderStatus.values()) {
                for (OrderStatus target : OrderStatus.values()) {
                    Order order = order(source);
                    if (source == OrderStatus.RESERVED && target != OrderStatus.RESERVED) {
                        Order changed = order.transitionTo(target, NOW.plusSeconds(1));
                        expectEquals(target, changed.status());
                        expectEquals(order.version() + 1, changed.version());
                    } else {
                        expectThrows(ValidationException.class, () -> order.transitionTo(target, NOW.plusSeconds(1)));
                    }
                }
            }
            Order reserved = order(OrderStatus.RESERVED);
            expectThrows(ValidationException.class, () -> new Order(reserved.id(), reserved.requestId(), reserved.sku(),
                    reserved.quantity(), reserved.fingerprint(), reserved.status(), NOW, NOW.minusSeconds(1),
                    NOW.plusSeconds(3600), 0));
            expectThrows(ValidationException.class, () -> OrderOperation.validate(null, "reason"));
            expectThrows(ValidationException.class, () -> OrderOperation.validate(OrderStatus.RESERVED, "reason"));
            expectThrows(ValidationException.class, () -> InventoryOperation.validate(null, 1, "reason"));
        });

        tests.test("inventory_linked_transitions_preserve_the_double_ledger", () -> {
            Inventory stock = new Inventory(SKU, new StockLevel(10), 0, NOW)
                    .reserve(new Quantity(3), NOW).release(new Quantity(2), NOW).confirm(new Quantity(1), NOW);
            expectEquals(9, stock.available().value());
            expectEquals(0, stock.reserved().value());
            expectEquals(3L, stock.version());
            expectThrows(ValidationException.class, () -> stock.release(new Quantity(1), NOW));
            expectThrows(ValidationException.class, () -> stock.confirm(new Quantity(1), NOW));
            Inventory capacity = new Inventory(SKU, new StockLevel(StockLevel.MAX - 1), 0, NOW)
                    .reserve(new Quantity(1), NOW);
            expectThrows(ValidationException.class, () -> capacity.release(new Quantity(2), NOW));
        });

        tests.test("role_permission_capability_matrix_is_exact", () -> {
            EnumMap<Role, Set<Permission>> expected = new EnumMap<>(Role.class);
            expected.put(Role.ADMIN, Set.of(Permission.values()));
            expected.put(Role.OPERATOR, Set.of(Permission.INVENTORY_READ, Permission.INVENTORY_WRITE,
                    Permission.ORDER_READ, Permission.ORDER_WRITE));
            expected.put(Role.VIEWER, Set.of(Permission.INVENTORY_READ, Permission.ORDER_READ));
            expected.put(Role.AUDITOR, Set.of(Permission.INVENTORY_READ, Permission.ORDER_READ,
                    Permission.AUDIT_READ, Permission.METRICS_READ));

            for (Role role : Role.values()) {
                Actor actor = new Actor("id-" + role, role.name(), Set.of(role));
                for (Permission permission : Permission.values()) {
                    boolean allowed = expected.get(role).contains(permission);
                    expectTrue(actor.has(permission) == allowed, role + " / " + permission);
                    if (allowed) {
                        actor.require(permission);
                    } else {
                        expectThrows(com.acme.trade.domain.exception.AccessDeniedException.class,
                                () -> actor.require(permission));
                    }
                }
            }
        });

        tests.test("password_policy_counts_utf8_bytes_not_only_characters", () -> {
            PasswordPolicy.validateForCreation("a".repeat(12));
            PasswordPolicy.validateForCreation("a".repeat(72));
            PasswordPolicy.validateForCreation("密".repeat(24));
            expectThrows(ValidationException.class, () -> PasswordPolicy.validateForCreation("a".repeat(11)));
            expectThrows(ValidationException.class, () -> PasswordPolicy.validateForCreation("a".repeat(73)));
            expectThrows(ValidationException.class, () -> PasswordPolicy.validateForCreation("密".repeat(25)));
            expectThrows(ValidationException.class, () -> PasswordPolicy.validateForCreation(null));
            expectTrue(PasswordPolicy.hasSafeLoginSize("密".repeat(24)));
            expectFalse(PasswordPolicy.hasSafeLoginSize("密".repeat(25)));
            expectFalse(PasswordPolicy.hasSafeLoginSize(""));
        });

        tests.test("role_sets_are_defensively_copied_and_credentials_have_no_value_tostring", () -> {
            Set<Role> mutable = new HashSet<>(Set.of(Role.VIEWER));
            Actor actor = new Actor("actor-id", "viewer", mutable);
            UserAccount user = new UserAccount(UUID.randomUUID(), "viewer", "secret-hash", true, mutable);
            mutable.add(Role.ADMIN);
            expectEquals(Set.of(Role.VIEWER), actor.roles());
            expectEquals(Set.of(Role.VIEWER), user.roles());
            expectThrows(UnsupportedOperationException.class, () -> actor.roles().add(Role.ADMIN));
            expectFalse(user.toString().contains("secret-hash"));
        });

        tests.test("fingerprints_and_token_hashes_are_stable_and_format_bounded", () -> {
            OrderFingerprint fingerprint = OrderFingerprint.of(SKU, new Quantity(3));
            expectEquals(fingerprint, OrderFingerprint.of(SKU, new Quantity(3)));
            expectFalse(fingerprint.equals(OrderFingerprint.of(SKU, new Quantity(4))));
            expectThrows(ValidationException.class, () -> new OrderFingerprint("A".repeat(64)));

            String token = "A".repeat(43);
            expectTrue(AccessTokenHash.isValidRawToken(token));
            expectFalse(AccessTokenHash.isValidRawToken("A".repeat(42)));
            expectFalse(AccessTokenHash.isValidRawToken("A".repeat(44)));
            expectFalse(AccessTokenHash.isValidRawToken("+".repeat(43)));
            expectEquals(64, AccessTokenHash.of(token).length());
            expectEquals(AccessTokenHash.of(token), AccessTokenHash.of(token));
        });

        tests.test("canonical_json_escapes_every_control_without_locale_dependent_formatting", () -> {
            expectEquals("{\"a\":\"\\u0000\\u0001\\u001f\",\"text\":\"中文\"}",
                    CanonicalJson.of(Map.of("text", "中文", "a", "\u0000\u0001\u001f")));
            Map<String, String> nullKey = new HashMap<>();
            nullKey.put(null, "value");
            expectThrows(IllegalArgumentException.class, () -> CanonicalJson.of(nullKey));
            Map<String, String> nullValue = new HashMap<>();
            nullValue.put("key", null);
            expectThrows(IllegalArgumentException.class, () -> CanonicalJson.of(nullValue));
        });
    }

    private static Order order(OrderStatus status) {
        Order reserved = Order.reserved(OrderId.random(), new RequestId("state-matrix"), SKU, new Quantity(1),
                NOW, NOW.plusSeconds(3600));
        return status == OrderStatus.RESERVED ? reserved : reserved.transitionTo(status, NOW);
    }
}

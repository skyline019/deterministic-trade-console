package concept;

import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.ResourceConflictException;
import com.acme.trade.application.exception.StockAdjustmentRejectedException;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import jtest.Concept;
import jtest.JTestRegistry;

import static concept.ConceptFixture.OPERATOR;
import static concept.ConceptFixture.SKU;
import static concept.ConceptFixture.VIEWER;
import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class InventoryCommandConcept implements Concept {
    @Override
    public String name() {
        return "InventoryCommands";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("create_adjust_and_replay_have_exact_receipts", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.inventoryService.create(OPERATOR, new RequestId("create-1"), SKU,
                    new StockLevel(10), "initial warehouse load");
            var adjusted = fixture.inventoryService.adjust(OPERATOR, new RequestId("adjust-1"), SKU,
                    -4, "damaged goods removed");
            var replay = fixture.inventoryService.adjust(OPERATOR, new RequestId("adjust-1"), SKU,
                    -4, "damaged goods removed");

            expectEquals(6, adjusted.operation().resultingStock().value());
            expectTrue(replay.replayed());
            expectEquals(6, fixture.available());
            expectEquals(2, fixture.audit.size());
        });

        tests.test("receipt_key_cannot_be_reused_with_another_delta_or_reason", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.inventoryService.create(OPERATOR, new RequestId("create-1"), SKU,
                    new StockLevel(10), "initial warehouse load");
            fixture.inventoryService.adjust(OPERATOR, new RequestId("adjust-1"), SKU, -1, "cycle count");

            expectThrows(IdempotencyConflictException.class, () -> fixture.inventoryService.adjust(
                    OPERATOR, new RequestId("adjust-1"), SKU, -2, "cycle count"));
            expectThrows(IdempotencyConflictException.class, () -> fixture.inventoryService.adjust(
                    OPERATOR, new RequestId("adjust-1"), SKU, -1, "another reason"));

            expectEquals(9, fixture.available());
            expectEquals(2, fixture.inventoryOperations.size());
        });

        tests.test("out_of_range_adjustment_is_atomic_noop", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.inventoryService.create(OPERATOR, new RequestId("create-1"), SKU,
                    new StockLevel(5), "initial warehouse load");

            expectThrows(StockAdjustmentRejectedException.class, () -> fixture.inventoryService.adjust(
                    OPERATOR, new RequestId("adjust-1"), SKU, -6, "invalid correction"));

            expectEquals(5, fixture.available());
            expectEquals(1, fixture.inventoryOperations.size());
        });


        tests.test("capacity_matrix_includes_reserved_stock_and_integer_extremes", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.inventoryService.create(OPERATOR, new RequestId("create-capacity"), SKU,
                    new StockLevel(StockLevel.MAX - 5), "capacity seed");
            fixture.service.place(fixture.command("reserve-capacity", 5));

            expectThrows(StockAdjustmentRejectedException.class, () -> fixture.inventoryService.adjust(
                    OPERATOR, new RequestId("adjust-overflow"), SKU, 6, "capacity overflow"));
            expectEquals(StockLevel.MAX - 10, fixture.available());
            expectEquals(5, fixture.reserved());

            var accepted = fixture.inventoryService.adjust(OPERATOR, new RequestId("adjust-exact-max"), SKU,
                    5, "exact capacity");
            expectEquals(StockLevel.MAX - 5, accepted.operation().resultingStock().value());
            expectThrows(ValidationException.class, () -> fixture.inventoryService.adjust(
                    OPERATOR, new RequestId("adjust-long-min"), SKU, Integer.MIN_VALUE, "overflow input"));
            expectEquals(StockLevel.MAX - 5, fixture.available());
        });

        tests.test("replayed_receipt_keeps_original_result_after_later_operations", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.inventoryService.create(OPERATOR, new RequestId("create-receipt"), SKU,
                    new StockLevel(10), "receipt seed");
            fixture.inventoryService.adjust(OPERATOR, new RequestId("adjust-receipt"), SKU, -2, "first correction");
            fixture.inventoryService.adjust(OPERATOR, new RequestId("adjust-later"), SKU, -1, "later correction");

            var replay = fixture.inventoryService.adjust(OPERATOR, new RequestId("adjust-receipt"), SKU,
                    -2, "first correction");

            expectTrue(replay.replayed());
            expectEquals(8, replay.operation().resultingStock().value());
            expectEquals(7, fixture.available());
            expectEquals(3, fixture.inventoryOperations.size());
        });

        tests.test("create_conflict_missing_sku_and_reason_matrix_fail_without_side_effects", () -> {
            ConceptFixture fixture = new ConceptFixture();
            fixture.inventoryService.create(OPERATOR, new RequestId("create-conflict"), SKU,
                    new StockLevel(10), "initial");
            expectThrows(ResourceConflictException.class, () -> fixture.inventoryService.create(
                    OPERATOR, new RequestId("create-other-key"), SKU, new StockLevel(20), "duplicate sku"));
            expectEquals(10, fixture.available());

            int reasonIndex = 0;
            for (String reason : new String[]{null, "", " ", " leading", "trailing ", "r".repeat(201)}) {
                String key = "bad-reason-" + reasonIndex++;
                expectThrows(ValidationException.class, () -> fixture.inventoryService.adjust(
                        OPERATOR, new RequestId(key), SKU, 1, reason));
            }
            expectThrows(StockAdjustmentRejectedException.class, () -> fixture.inventoryService.adjust(
                    OPERATOR, new RequestId("missing-sku"), Sku.of("MISSING"), 1, "missing"));
            expectEquals(1, fixture.inventoryOperations.size());
            expectEquals(1, fixture.audit.size());
        });

        tests.test("viewer_cannot_adjust_inventory_and_reads_are_sorted", () -> {
            ConceptFixture fixture = new ConceptFixture();
            expectThrows(AccessDeniedException.class, () -> fixture.inventoryService.create(
                    VIEWER, new RequestId("create-v"), SKU, new StockLevel(1), "forbidden write"));
            expectTrue(fixture.inventory.find(SKU).isEmpty());

            fixture.inventoryService.create(OPERATOR, new RequestId("create-z"), Sku.of("SKU-Z"), new StockLevel(1), "z");
            fixture.inventoryService.create(OPERATOR, new RequestId("create-a"), Sku.of("SKU-A"), new StockLevel(2), "a");
            var values = fixture.inventoryService.list(VIEWER);
            expectEquals("SKU-A", values.get(0).sku().value());
            expectEquals("SKU-Z", values.get(1).sku().value());
        });
    }
}

-- Quantity affected by a confirmation/cancellation (partial confirm uses CONFIRMED + effect_quantity < order qty).
ALTER TABLE order_operations
    ADD COLUMN effect_quantity INT NOT NULL DEFAULT 0 AFTER target_status;

-- 0 = legacy rows (pre-partial-confirm); new writes always store a positive effect_quantity.
ALTER TABLE order_operations
    ADD CONSTRAINT chk_order_operation_effect_quantity CHECK (effect_quantity >= 0);

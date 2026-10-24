-- Independent idempotency receipts for reservation reschedule (ADR 003).
CREATE TABLE order_reschedule_operations (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY,
    order_id CHAR(36) NOT NULL,
    new_expires_at TIMESTAMP(6) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    actor_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_order_reschedule_order FOREIGN KEY (order_id) REFERENCES orders (id),
    INDEX idx_order_reschedule_order (order_id, created_at)
) ENGINE = InnoDB;

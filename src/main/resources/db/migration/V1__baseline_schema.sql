CREATE TABLE inventory (
    sku VARCHAR(64) NOT NULL PRIMARY KEY,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_inventory_available CHECK (available_quantity >= 0),
    CONSTRAINT chk_inventory_reserved CHECK (reserved_quantity >= 0),
    CONSTRAINT chk_inventory_capacity CHECK (available_quantity + reserved_quantity <= 100000000),
    CONSTRAINT chk_inventory_version CHECK (version >= 0)
) ENGINE = InnoDB;

CREATE TABLE orders (
    id CHAR(36) NOT NULL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_orders_request_id UNIQUE (request_id),
    CONSTRAINT fk_orders_inventory_sku FOREIGN KEY (sku) REFERENCES inventory (sku),
    CONSTRAINT chk_orders_quantity CHECK (quantity > 0),
    CONSTRAINT chk_orders_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT chk_orders_version CHECK (version >= 0),
    INDEX idx_orders_cursor (created_at, id),
    INDEX idx_orders_status_cursor (status, created_at, id),
    INDEX idx_orders_sku_cursor (sku, created_at, id),
    INDEX idx_orders_status_sku_cursor (status, sku, created_at, id)
) ENGINE = InnoDB;

CREATE TABLE inventory_operations (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY,
    sku VARCHAR(64) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    delta INT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    resulting_stock INT NOT NULL,
    actor_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_inventory_operations_sku FOREIGN KEY (sku) REFERENCES inventory (sku),
    CONSTRAINT chk_inventory_operation_type CHECK (operation_type IN ('CREATE', 'ADJUST')),
    INDEX idx_inventory_operations_sku (sku, created_at)
) ENGINE = InnoDB;

CREATE TABLE order_operations (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY,
    order_id CHAR(36) NOT NULL,
    target_status VARCHAR(16) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    actor_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_order_operations_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_order_operation_target CHECK (target_status IN ('CONFIRMED', 'CANCELLED')),
    INDEX idx_order_operations_order (order_id, created_at)
) ENGINE = InnoDB;

CREATE TABLE users (
    id CHAR(36) NOT NULL PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(72) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    roles VARCHAR(128) NOT NULL,
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE = InnoDB;

CREATE TABLE api_tokens (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_api_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_api_tokens_expiry (expires_at)
) ENGINE = InnoDB;

CREATE TABLE audit_events (
    id CHAR(36) NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(16) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    sequence BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    actor_name VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    previous_hash CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL,
    CONSTRAINT uk_audit_aggregate_sequence UNIQUE (aggregate_type, aggregate_id, sequence),
    CONSTRAINT chk_audit_sequence CHECK (sequence > 0),
    INDEX idx_audit_time (occurred_at)
) ENGINE = InnoDB;

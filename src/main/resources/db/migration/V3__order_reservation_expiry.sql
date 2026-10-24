-- Reservation TTL: RESERVED orders carry expires_at; ops expire-reservations cancels via same ledger path.
ALTER TABLE orders
    ADD COLUMN expires_at TIMESTAMP(6) NULL AFTER updated_at;

-- Backfill existing RESERVED rows (lab/demo): 24h from created_at.
UPDATE orders
SET expires_at = TIMESTAMPADD(HOUR, 24, created_at)
WHERE status = 'RESERVED' AND expires_at IS NULL;

-- Terminal rows keep NULL expires_at (historical) or retain value if already set.
CREATE INDEX idx_orders_expire_reserved ON orders (status, expires_at, id);

-- Order timestamp invariants the domain already enforces in `Order`'s constructor:
--   * updated_at >= created_at (monotonic timestamps)
--   * a RESERVED row carries a deadline strictly after its creation
--
-- Until V7 these were domain-only, so a single bad write (or any hand-rolled UPDATE) could store a
-- row that MySqlOrderRepository then refuses to materialise: the expiry sweep and every read path
-- that touches that row would fail. Enforcing them in InnoDB makes the bad row unwritable instead of
-- merely detectable.
--
-- Pre-flight (run before deploying to an existing database; the ALTER fails if any row violates):
--   SELECT id, status, created_at, updated_at, expires_at FROM orders
--    WHERE updated_at < created_at
--       OR (status = 'RESERVED' AND (expires_at IS NULL OR expires_at <= created_at));
-- Such rows are pre-existing corruption: decide per row whether to cancel or reschedule it before
-- adding the constraints. See docs/migrations.md.
ALTER TABLE orders
    ADD CONSTRAINT chk_orders_updated_after_created CHECK (updated_at >= created_at),
    ADD CONSTRAINT chk_orders_reserved_expiry
        CHECK (status <> 'RESERVED' OR (expires_at IS NOT NULL AND expires_at > created_at));

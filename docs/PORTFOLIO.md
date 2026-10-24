# Portfolio notes

Public repo: https://github.com/skyline019/deterministic-trade-console

## Elevator pitch

Built a single-warehouse inventory trading console where **MySQL is the only stock fact source**, commands are **idempotent**, and architecture is **explicit hexagonal** (no annotation-driven “magic” in the core). Proof is multi-layer: ArchUnit, concept jtest, Testcontainers IT, Playwright.

## Interview talking points

1. Why Redis does not store inventory (fail-closed mutex only).
2. Unique-key race: leave the write transaction, then replay the winner.
3. Partial confirm vs cancel vs expire — same ledger semantics, separate receipt namespaces.
4. AuthN port (opaque / HS256 / static JWKS) vs Application RBAC.
5. Honest non-goals: ADR 001 (no multi-active / full IdP).

## Screenshots

See `docs/assets/*.png` and `architecture.svg` (linked from README).

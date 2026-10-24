#!/usr/bin/env sh
set -eu
# One-shot: cancel expired RESERVED orders via the same ledger path as manual cancel.
# No cron / @Scheduled. See ADR 003 / ReservationExpiryConcept.
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$ROOT"
export BOOTSTRAP_ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-local-admin-only}"
BATCH="${TRADE_MAINTENANCE_BATCH_SIZE:-500}"

docker compose run --rm --no-deps \
  -e BOOTSTRAP_ADMIN_PASSWORD \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e TRADE_MAINTENANCE_COMMAND=expire-reservations \
  -e TRADE_MAINTENANCE_EXIT_AFTER=true \
  -e TRADE_MAINTENANCE_BATCH_SIZE="$BATCH" \
  app

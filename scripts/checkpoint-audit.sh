#!/usr/bin/env sh
set -eu
# One-shot: append CHAIN_CHECKPOINT on long live audit chains (explicit; no in-app cron).
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$ROOT"
export BOOTSTRAP_ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-local-admin-only}"
BATCH="${TRADE_MAINTENANCE_BATCH_SIZE:-50}"
MIN_EVENTS="${TRADE_MAINTENANCE_CHECKPOINT_MIN_EVENTS:-500}"

docker compose run --rm --no-deps \
  -e BOOTSTRAP_ADMIN_PASSWORD \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e TRADE_MAINTENANCE_COMMAND=checkpoint-audit \
  -e TRADE_MAINTENANCE_EXIT_AFTER=true \
  -e TRADE_MAINTENANCE_BATCH_SIZE="$BATCH" \
  -e TRADE_MAINTENANCE_CHECKPOINT_MIN_EVENTS="$MIN_EVENTS" \
  app

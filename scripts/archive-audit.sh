#!/usr/bin/env sh
set -eu
# One-shot: archive quiet aggregate chains (MAX(occurred_at) older than quiet window).
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$ROOT"
export BOOTSTRAP_ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-local-admin-only}"
BATCH="${TRADE_MAINTENANCE_BATCH_SIZE:-100}"
QUIET="${TRADE_MAINTENANCE_AUDIT_QUIET_BEFORE:-P30D}"

docker compose run --rm --no-deps \
  -e BOOTSTRAP_ADMIN_PASSWORD \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e TRADE_MAINTENANCE_COMMAND=archive-audit \
  -e TRADE_MAINTENANCE_EXIT_AFTER=true \
  -e TRADE_MAINTENANCE_BATCH_SIZE="$BATCH" \
  -e TRADE_MAINTENANCE_AUDIT_QUIET_BEFORE="$QUIET" \
  app

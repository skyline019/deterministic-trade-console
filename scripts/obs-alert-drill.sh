#!/usr/bin/env sh
# Prove METRICS_READ scrape of /actuator/prometheus, then fire TradeInstanceDown once.
# Prerequisite: docker compose up -d (app healthy). This script enables --profile obs.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$ROOT"
BASE="${BASE_URL:-http://127.0.0.1:8080}"
PROM="${PROM_URL:-http://127.0.0.1:9090}"
PASS="${BOOTSTRAP_ADMIN_PASSWORD:-local-admin-only}"
TOKEN_FILE="$ROOT/ops/secrets/metrics_bearer.token"

echo "== obs drill: app health =="
curl -fsS "$BASE/actuator/health" | grep -q '"status":"UP"'

echo "== obs drill: issue METRICS_READ bearer (admin) =="
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PASS\",\"delivery\":\"bearer\"}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
test -n "$TOKEN"
printf '%s\n' "$TOKEN" > "$TOKEN_FILE"
chmod 600 "$TOKEN_FILE" 2>/dev/null || true

echo "== obs drill: seed one order so trade_order_requests_total exists =="
SKU="OBS-$(date +%s)"
curl -fsS -X POST "$BASE/api/v1/inventory" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: obs-inv-$SKU" \
  -d "{\"sku\":\"$SKU\",\"initialStock\":3,\"reason\":\"obs drill\"}" >/dev/null
curl -fsS -X POST "$BASE/api/v1/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: obs-ord-$SKU" \
  -d "{\"sku\":\"$SKU\",\"quantity\":1}" >/dev/null

echo "== obs drill: direct actuator scrape (auth) =="
curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/actuator/prometheus" \
  | grep -q 'trade_order_requests_total'

echo "== obs drill: start prometheus + alertmanager =="
# Ensure mount target is a file (Compose turns missing paths into directories).
test -f "$TOKEN_FILE"
docker compose --profile obs up -d prometheus alertmanager 2>&1 | tail -20

echo "== obs drill: reload prometheus config =="
i=0
while [ "$i" -lt 30 ]; do
  if curl -fsS -X POST "$PROM/-/reload" >/dev/null 2>&1; then
    break
  fi
  i=$((i + 1))
  sleep 1
done

echo "== obs drill: wait scrape up + business metric =="
up_ok=0
metric_ok=0
j=0
while [ "$j" -lt 60 ]; do
  up=$(curl -fsS --get "$PROM/api/v1/query" --data-urlencode 'query=up{job="deterministic-trade"}' \
    | sed -n 's/.*"value":\[[0-9.]*,"\([0-9.]*\)"\].*/\1/p' | head -1)
  if [ "$up" = "1" ]; then
    up_ok=1
  fi
  # Micrometer counter may appear as trade_order_requests_total or …_total with labels only in /federate style — query sum().
  hit=$(curl -fsS --get "$PROM/api/v1/query" --data-urlencode 'query=sum(trade_order_requests_total)' \
    | grep -c '"result":\[{' || true)
  if [ "${hit:-0}" -ge 1 ]; then
    metric_ok=1
  fi
  if [ "$up_ok" = "1" ] && [ "$metric_ok" = "1" ]; then
    break
  fi
  j=$((j + 1))
  sleep 2
done
echo "scrape_up=$up_ok metric_present=$metric_ok"
test "$up_ok" = "1"
test "$metric_ok" = "1"

echo "== obs drill: stop app to fire TradeInstanceDown (for: 20s) =="
docker compose stop app
fire_ok=0
k=0
while [ "$k" -lt 50 ]; do
  # ALERTS metric is the reliable firing signal once 'for' elapses.
  hit=$(curl -fsS --get "$PROM/api/v1/query" \
    --data-urlencode 'query=ALERTS{alertname="TradeInstanceDown",alertstate="firing"}' \
    | grep -c '"metric"' || true)
  if [ "${hit:-0}" -ge 1 ]; then
    fire_ok=1
    break
  fi
  k=$((k + 1))
  sleep 2
done
echo "alert_firing=$fire_ok"

echo "== obs drill: restore app =="
docker compose start app
ok=0
for _ in $(seq 1 60); do
  if curl -sf "$BASE/actuator/health" | grep -q '"status":"UP"'; then
    ok=1
    break
  fi
  sleep 2
done
test "$ok" = "1"
test "$fire_ok" = "1"

echo "OBS_ALERT_DRILL_OK scrape=actuator/prometheus metric=trade_order_requests_total alert=TradeInstanceDown"

#!/usr/bin/env sh
# Redis Sentinel failover drill. Correctness: locks remain fail-closed; after failover, writes succeed again.
# Prerequisite:
#   docker compose -f docker-compose.yml -f docker-compose.ha.yml up --build -d
#   wait until app /actuator/health is UP
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$ROOT"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.ha.yml"
BASE="${BASE_URL:-http://127.0.0.1:8080}"
PASS="${BOOTSTRAP_ADMIN_PASSWORD:-local-admin-only}"

echo "== HA drill: health =="
curl -fsS "$BASE/actuator/health" | grep -q '"status":"UP"'

echo "== HA drill: sentinel master before kill =="
BEFORE=$($COMPOSE exec -T redis-sentinel redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster | tr '\n' ' ')
echo "master_before=$BEFORE"
echo "$BEFORE" | grep -q .

echo "== HA drill: bearer login + place order (pre-failover) =="
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PASS\",\"delivery\":\"bearer\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
test -n "$TOKEN"
SKU="HA-DRILL-$(date +%s)"
curl -fsS -X POST "$BASE/api/v1/inventory" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: ha-inv-$SKU" \
  -d "{\"sku\":\"$SKU\",\"initialStock\":5,\"reason\":\"ha drill seed\"}" >/dev/null

echo "== HA drill: kill redis (master service) =="
# Killing the master *container* drops DNS name "redis"; Sentinel must promote replica to a new IP.
$COMPOSE kill redis

echo "== HA drill: wait for sentinel to elect new master (addr must change) =="
i=0
AFTER=""
while [ "$i" -lt 60 ]; do
  AFTER=$($COMPOSE exec -T redis-sentinel redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster 2>/dev/null | tr '\n' ' ' || true)
  if [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ]; then
    # New master must answer PING (promoted replica), not a stale unreachable addr.
    NEW_IP=$(echo "$AFTER" | awk '{print $1}')
    NEW_PORT=$(echo "$AFTER" | awk '{print $2}')
    if [ -n "$NEW_IP" ] && [ -n "$NEW_PORT" ]; then
      if $COMPOSE exec -T redis-sentinel redis-cli -h "$NEW_IP" -p "$NEW_PORT" PING 2>/dev/null | grep -q PONG; then
        break
      fi
    fi
  fi
  i=$((i + 1))
  sleep 1
done
echo "master_after=$AFTER"
test -n "$AFTER"
test "$AFTER" != "$BEFORE"

echo "== HA drill: write path after failover =="
# Brief retries: Lettuce rediscovers via Sentinel; fail-closed may 503 during the window.
ok=0
j=0
while [ "$j" -lt 30 ]; do
  code=$(curl -s -o /tmp/ha_place.json -w '%{http_code}' -X POST "$BASE/api/v1/orders" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: ha-ord-$SKU-$j" \
    -d "{\"sku\":\"$SKU\",\"quantity\":1}")
  if [ "$code" = "200" ] || [ "$code" = "201" ]; then
    ok=1
    break
  fi
  j=$((j + 1))
  sleep 1
done
test "$ok" = "1"
echo "HA_FAILOVER_OK master_before=[$BEFORE] master_after=[$AFTER] place_attempts=$((j + 1))"
echo "== HA drill: restore hint =="
echo "Post-kill topology is split until recreate. Restore HA lab:"
echo "  $COMPOSE up -d --force-recreate redis redis-replica redis-sentinel app"
echo "Or return to non-HA baseline:"
echo "  docker compose -f docker-compose.yml -f docker-compose.ha.yml stop redis-replica redis-sentinel"
echo "  docker compose -f docker-compose.yml -f docker-compose.ha.yml rm -f redis-replica redis-sentinel"
echo "  docker compose up -d --force-recreate redis app"

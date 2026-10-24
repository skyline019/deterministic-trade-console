#!/usr/bin/env sh
set -eu
BASE_URL=${1:-http://localhost:8080}
ADMIN_USERNAME=${BOOTSTRAP_ADMIN_USERNAME:-admin}
ADMIN_PASSWORD=${BOOTSTRAP_ADMIN_PASSWORD:-local-admin-only}
RUN_ID="smoke-$(date +%s)-$$"
SKU="SKU-SMOKE-$(date +%s)-$$"
TMP_DIR=$(mktemp -d)
TOKEN=''
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

printf '%s\n' '--- authenticate admin ---'
login_status=$(curl -sS -o "$TMP_DIR/login.json" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}")
[ "$login_status" = '200' ]
TOKEN=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$TMP_DIR/login.json")
[ -n "$TOKEN" ]
AUTH="Authorization: Bearer $TOKEN"

printf '%s\n' '--- create isolated SKU with stock=10 ---'
create_stock_status=$(curl -sS -o "$TMP_DIR/stock.json" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/inventory" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RUN_ID-create" \
    -d "{\"sku\":\"$SKU\",\"initialStock\":10,\"reason\":\"authenticated smoke seed\"}")
cat "$TMP_DIR/stock.json"
[ "$create_stock_status" = '201' ]

printf '\n%s\n' '--- create order quantity=3 ---'
create_status=$(curl -sS -o "$TMP_DIR/create.json" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/orders" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RUN_ID" -d "{\"sku\":\"$SKU\",\"quantity\":3}")
cat "$TMP_DIR/create.json"
[ "$create_status" = '201' ]
ORDER_ID=$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' "$TMP_DIR/create.json")
[ -n "$ORDER_ID" ]

printf '\n%s\n' '--- replay same key and payload ---'
replay_status=$(curl -sS -o "$TMP_DIR/replay.json" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/orders" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RUN_ID" -d "{\"sku\":\"$SKU\",\"quantity\":3}")
cat "$TMP_DIR/replay.json"
[ "$replay_status" = '200' ]

printf '\n%s\n' '--- reject same key with another payload ---'
conflict_status=$(curl -sS -o "$TMP_DIR/conflict.json" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/orders" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RUN_ID" -d "{\"sku\":\"$SKU\",\"quantity\":4}")
cat "$TMP_DIR/conflict.json"
[ "$conflict_status" = '409' ]

printf '\n%s\n' '--- reserved ledger must be available=7, reserved=3 ---'
curl -fsS -H "$AUTH" "$BASE_URL/api/v1/inventory/$SKU" > "$TMP_DIR/inventory.json"
cat "$TMP_DIR/inventory.json"
grep -q '"available":7' "$TMP_DIR/inventory.json"
grep -q '"reserved":3' "$TMP_DIR/inventory.json"

printf '\n%s\n' '--- cancel once, then replay cancellation ---'
cancel_status=$(curl -sS -o "$TMP_DIR/cancel.json" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/orders/$ORDER_ID/cancellation" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RUN_ID-cancel" -d '{"reason":"smoke cancellation"}')
[ "$cancel_status" = '200' ]
cancel_replay_status=$(curl -sS -o "$TMP_DIR/cancel-replay.json" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/orders/$ORDER_ID/cancellation" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RUN_ID-cancel" -d '{"reason":"smoke cancellation"}')
[ "$cancel_replay_status" = '200' ]
curl -fsS -H "$AUTH" "$BASE_URL/api/v1/inventory/$SKU" > "$TMP_DIR/released.json"
grep -q '"available":10' "$TMP_DIR/released.json"
grep -q '"reserved":0' "$TMP_DIR/released.json"

printf '\n%s\n' '--- verify aggregate audit chain ---'
curl -fsS -H "$AUTH" "$BASE_URL/api/v1/audit/verification?type=ORDER&id=$ORDER_ID" > "$TMP_DIR/audit.json"
cat "$TMP_DIR/audit.json"
grep -q '"valid":true' "$TMP_DIR/audit.json"
grep -q '"checkedEvents":2' "$TMP_DIR/audit.json"

printf '\n%s\n' '--- revoke token; next call must be 401 ---'
revoke_status=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "$AUTH" "$BASE_URL/api/v1/auth/token")
[ "$revoke_status" = '204' ]
after_revoke=$(curl -sS -o /dev/null -w '%{http_code}' -H "$AUTH" "$BASE_URL/api/v1/inventory")
[ "$after_revoke" = '401' ]
TOKEN=''
printf '\nSMOKE OK\n'

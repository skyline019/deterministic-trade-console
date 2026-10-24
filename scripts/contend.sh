#!/usr/bin/env sh
set -eu
BASE_URL=${1:-http://localhost:8080}
ADMIN_USERNAME=${BOOTSTRAP_ADMIN_USERNAME:-admin}
ADMIN_PASSWORD=${BOOTSTRAP_ADMIN_PASSWORD:-local-admin-only}
RUN_ID="contend-$(date +%s)-$$"
SKU="SKU-LOAD-$(date +%s)-$$"
TMP_DIR=$(mktemp -d)
TOKEN=''
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

curl -fsS -X POST "$BASE_URL/api/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" > "$TMP_DIR/login.json"
TOKEN=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$TMP_DIR/login.json")
[ -n "$TOKEN" ]
AUTH="Authorization: Bearer $TOKEN"
export BASE_URL RUN_ID TMP_DIR AUTH SKU

curl -fsS -X POST "$BASE_URL/api/v1/inventory" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RUN_ID-create" \
    -d "{\"sku\":\"$SKU\",\"initialStock\":50,\"reason\":\"authenticated contention seed\"}" > /dev/null

# Every task writes its own status file; there is no concurrent append race.
seq 1 100 | xargs -P 16 -I '{}' sh -c '
    curl -sS -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/orders" -H "$AUTH" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: $RUN_ID-$1" \
        -d "{\"sku\":\"$SKU\",\"quantity\":1}" > "$TMP_DIR/$1"
' _ '{}'

created=$(grep -h '^201$' "$TMP_DIR"/* | wc -l)
out_of_stock=$(grep -h '^409$' "$TMP_DIR"/* | wc -l)
[ "$created" -eq 50 ]
[ "$out_of_stock" -eq 50 ]

curl -fsS -H "$AUTH" "$BASE_URL/api/v1/inventory/$SKU" > "$TMP_DIR/inventory"
grep -q '"available":0' "$TMP_DIR/inventory"
grep -q '"reserved":50' "$TMP_DIR/inventory"
curl -fsS -o /dev/null -X DELETE -H "$AUTH" "$BASE_URL/api/v1/auth/token"
TOKEN=''
printf 'CONTENTION OK: created=%s out_of_stock=%s available=0 reserved=50\n' "$created" "$out_of_stock"

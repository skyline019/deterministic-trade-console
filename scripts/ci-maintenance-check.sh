#!/usr/bin/env sh
set -eu
# End-to-end maintenance proof against a real Compose stack (CI job "compose-stack").
#
# Covers the three things a unit test cannot reach without MySQL:
#   1. archive-audit relocates a chain instead of truncating it (INV-014): reads span
#      audit_events + audit_events_archive, so a post-archive append continues at the archived tip
#      and verification still reports the archived prefix instead of "no events".
#   2. expire-reservations cancels through the same ledger as a manual cancel and releases stock.
#   3. the V6 archive-eligibility index and the V7 timestamp invariants actually ship with the migrations.
#   4. a row that violates a domain invariant is reported by the expiry sweep (not fatal to the batch) and
#      surfaces as 500 STORED_STATE_INVALID on read, while the same write is rejected while V7 is enforced.
#
# Usage: ./scripts/ci-maintenance-check.sh [BASE_URL]
BASE_URL=${1:-http://127.0.0.1:8080}
ADMIN_USERNAME=${BOOTSTRAP_ADMIN_USERNAME:-admin}
ADMIN_PASSWORD=${BOOTSTRAP_ADMIN_PASSWORD:-local-admin-only}
RUN_ID="maint-$(date +%s)-$$"
SKU="SKU-MAINT-$(date +%s)-$$"
TMP_DIR=$(mktemp -d)
# Stable path so the CI failure step can annotate it (see .github/workflows/ci.yml).
LOG=${MAINTENANCE_CHECK_LOG:-/tmp/maintenance-check.log}
: > "$LOG"
TOKEN=''
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

fail() {
    printf 'MAINTENANCE CHECK FAILED: %s\n' "$1" >&2
    printf 'MAINTENANCE CHECK FAILED: %s\n' "$1" >> "$LOG"
    # ::error:: makes the reason a PR annotation instead of a log line nobody can open.
    printf '::error title=maintenance drill::%s\n' "$1" >&2
    exit 1
}

# MySQL client inside the Compose service, independent of the container name.
note() {
    printf '%s\n' "$1" >> "$LOG"
}

sql() {
    docker compose exec -T mysql sh -c \
        'mysql --batch --skip-column-names -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "$1"' _ "$1"
}

api() {
    # api METHOD PATH [BODY...] -> prints body, exits non-zero on transport failure
    method=$1
    path=$2
    shift 2
    curl -sS -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $TOKEN" "$@"
}

expect_status() {
    expected=$1
    actual=$2
    what=$3
    [ "$actual" = "$expected" ] || fail "$what returned HTTP $actual, expected $expected"
}

maintenance() {
    command=$1
    shift
    printf '=== maintenance %s ===\n' "$command" >> "$LOG"
    docker compose run --rm --no-deps \
        -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
        -e TRADE_MAINTENANCE_COMMAND="$command" \
        -e TRADE_MAINTENANCE_EXIT_AFTER=true \
        "$@" app 2>&1 | tee "$TMP_DIR/$command.log" >&2
    cat "$TMP_DIR/$command.log" >> "$LOG"
    grep -q 'MAINTENANCE_OK' "$TMP_DIR/$command.log" \
        || fail "maintenance command '$command' did not report MAINTENANCE_OK"
    sed -n 's/.*MAINTENANCE_OK .*affected=\([0-9][0-9]*\).*/\1/p' "$TMP_DIR/$command.log" | tail -1
}

printf '%s\n' '--- authenticate admin ---'
login_status=$(curl -sS -o "$TMP_DIR/login.json" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}")
expect_status 200 "$login_status" 'login'
TOKEN=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$TMP_DIR/login.json")
[ -n "$TOKEN" ] || fail 'login did not return an access token'

printf '%s\n' '=== V6 archive scan index is present with the intended column order ==='
# information_schema.statistics has one row per index *column*, so count the shape, not the rows:
# the scan is only useful if occurred_at is part of the (aggregate_type, aggregate_id) prefix.
index_columns=$(sql "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'audit_events'
      AND index_name = 'idx_audit_aggregate_occurred'")
note "idx_audit_aggregate_occurred columns=$index_columns"
[ "$index_columns" = 'aggregate_type,aggregate_id,occurred_at,sequence' ] \
    || fail "idx_audit_aggregate_occurred has columns '$index_columns', expected aggregate_type,aggregate_id,occurred_at,sequence"

printf '%s\n' '=== seed stock and two reservations ==='
expect_status 201 "$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/inventory" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $RUN_ID-create" \
    -d "{\"sku\":\"$SKU\",\"initialStock\":5,\"reason\":\"maintenance drill seed\"}")" 'create sku'

place() {
    curl -sS -X POST "$BASE_URL/api/v1/orders" -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -H "Idempotency-Key: $RUN_ID-$1" \
        -d "{\"sku\":\"$SKU\",\"quantity\":$2}"
}
ORDER_A=$(place order-a 2 | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
ORDER_B=$(place order-b 1 | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
[ -n "$ORDER_A" ] && [ -n "$ORDER_B" ] || fail 'failed to place the drill orders'

assert_ledger() {
    api GET "/api/v1/inventory/$SKU" > "$TMP_DIR/inventory.json"
    grep -q "\"available\":$1" "$TMP_DIR/inventory.json" \
        || fail "expected available=$1, got: $(cat "$TMP_DIR/inventory.json")"
    grep -q "\"reserved\":$2" "$TMP_DIR/inventory.json" \
        || fail "expected reserved=$2, got: $(cat "$TMP_DIR/inventory.json")"
}
assert_ledger 2 3

printf '%s\n' '=== archive quiet chains (quiet window PT1S) ==='
sleep 2
archived=$(maintenance archive-audit -e TRADE_MAINTENANCE_AUDIT_QUIET_BEFORE=PT1S)
[ "${archived:-0}" -ge 1 ] || fail "archive-audit archived $archived aggregates, expected >= 1"

archive_rows=$(sql 'SELECT COUNT(*) FROM audit_events_archive')
note "archive_rows=$archive_rows after archive-audit"
[ "$archive_rows" -ge 1 ] || fail 'archive table is empty after archive-audit'
order_b_archived=$(sql "SELECT COUNT(*) FROM audit_events_archive
    WHERE aggregate_type = 'ORDER' AND aggregate_id = '$ORDER_B'")
[ "$order_b_archived" -ge 1 ] || fail "order B chain was not relocated to the archive (rows=$order_b_archived)"

printf '%s\n' '=== verification still covers the archived prefix (INV-014) ==='
api GET "/api/v1/audit/verification?type=ORDER&id=$ORDER_A" > "$TMP_DIR/verify-a.json"
grep -q '"valid":true' "$TMP_DIR/verify-a.json" \
    || fail "archived chain no longer verifies: $(cat "$TMP_DIR/verify-a.json")"
grep -q '"checkedEvents":1' "$TMP_DIR/verify-a.json" \
    || fail "archived chain reported the wrong event count (expected 1): $(cat "$TMP_DIR/verify-a.json")"

printf '%s\n' '=== expire-reservations cancels through the cancel ledger ==='
# Age the whole reservation instead of just the deadline: an order created two hours ago with a
# one-hour TTL is exactly what the sweep exists for. Moving only expires_at into the past would
# produce a row that violates Order's own invariant (expiresAt must be after createdAt), which the
# domain rejects on read — a corrupt row is not the same thing as an expired one.
sql "UPDATE orders
        SET created_at = NOW(6) - INTERVAL 2 HOUR,
            updated_at = NOW(6) - INTERVAL 2 HOUR,
            expires_at = NOW(6) - INTERVAL 1 MINUTE
      WHERE id = '$ORDER_B'" > /dev/null
expired_summary=$(maintenance expire-reservations)
grep -q 'failed=0' "$TMP_DIR/expire-reservations.log" \
    || fail "expire sweep reported failures: $(cat "$TMP_DIR/expire-reservations.log")"
[ "${expired_summary:-0}" -ge 1 ] || fail "expire-reservations applied to $expired_summary orders, expected >= 1"

api GET "/api/v1/orders/$ORDER_B" > "$TMP_DIR/order-b.json"
grep -q '"status":"CANCELLED"' "$TMP_DIR/order-b.json" \
    || fail "expired order was not cancelled: $(cat "$TMP_DIR/order-b.json")"
assert_ledger 3 2

printf '%s\n' '=== the post-archive append continues the relocated chain ==='
api GET "/api/v1/audit/verification?type=ORDER&id=$ORDER_B" > "$TMP_DIR/verify-b.json"
grep -q '"valid":true' "$TMP_DIR/verify-b.json" \
    || fail "order B chain failed verification: $(cat "$TMP_DIR/verify-b.json")"
grep -q '"checkedEvents":2' "$TMP_DIR/verify-b.json" \
    || fail "order B chain must span archive + live (2 events): $(cat "$TMP_DIR/verify-b.json")"

live_b=$(sql "SELECT COUNT(*) FROM audit_events WHERE aggregate_type = 'ORDER' AND aggregate_id = '$ORDER_B'")
archived_b=$(sql "SELECT COUNT(*) FROM audit_events_archive WHERE aggregate_type = 'ORDER' AND aggregate_id = '$ORDER_B'")
[ "$live_b" = '1' ] && [ "$archived_b" = '1' ] \
    || fail "expected exactly one archived + one live event for order B (live=$live_b archived=$archived_b)"

printf '%s\n' '=== V7 timestamp invariants ship with the migrations ==='
check_constraints=$(sql "SELECT GROUP_CONCAT(constraint_name ORDER BY constraint_name)
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'orders' AND constraint_type = 'CHECK'
      AND constraint_name IN ('chk_orders_updated_after_created', 'chk_orders_reserved_expiry')")
note "orders check constraints=$check_constraints"
[ "$check_constraints" = 'chk_orders_reserved_expiry,chk_orders_updated_after_created' ] \
    || fail "V7 check constraints are '$check_constraints', expected chk_orders_reserved_expiry,chk_orders_updated_after_created"

printf '%s\n' '=== V7 rejects a reservation that expires before it was created ==='
# An enforced constraint is the reason the sweep below only ever meets *legacy* corruption.
if sql "UPDATE orders SET expires_at = created_at - INTERVAL 1 MINUTE WHERE id = '$ORDER_A'" >/dev/null 2>&1; then
    fail 'V7 chk_orders_reserved_expiry accepted expires_at <= created_at'
fi
# ...and it stays enforced after a rejected write (InnoDB rejects the statement, not the row silently).
still_valid=$(sql "SELECT COUNT(*) FROM orders WHERE id = '$ORDER_A' AND expires_at > created_at")
[ "$still_valid" = '1' ] || fail 'a rejected write must leave the stored row untouched'

printf '%s\n' '=== one unreadable row degrades the sweep instead of wedging it ==='
# Reproduce pre-V7 (or out-of-band) corruption on purpose: disable the invariant for one write only.
sql 'ALTER TABLE orders ALTER CHECK chk_orders_reserved_expiry NOT ENFORCED' > /dev/null
sql "UPDATE orders
        SET created_at = NOW(6) - INTERVAL 2 HOUR,
            updated_at = NOW(6) - INTERVAL 2 HOUR,
            expires_at = NOW(6) - INTERVAL 3 HOUR
      WHERE id = '$ORDER_A'" > /dev/null
# A second, healthy reservation in the same batch: it must still be released.
ORDER_C=$(curl -sS -X POST "$BASE_URL/api/v1/orders" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $RUN_ID-order-c" \
    -d "{\"sku\":\"$SKU\",\"quantity\":1}" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
[ -n "$ORDER_C" ] || fail 'failed to place the healthy companion order'
sql "UPDATE orders
        SET created_at = NOW(6) - INTERVAL 2 HOUR,
            updated_at = NOW(6) - INTERVAL 2 HOUR,
            expires_at = NOW(6) - INTERVAL 1 MINUTE
      WHERE id = '$ORDER_C'" > /dev/null

# Reading the corrupt row is a server-side defect, not a caller error: 500, never 400.
corrupt_status=$(curl -sS -o "$TMP_DIR/corrupt-order.json" -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/orders/$ORDER_A")
expect_status 500 "$corrupt_status" 'reading a corrupt stored order'
grep -q '"code":"STORED_STATE_INVALID"' "$TMP_DIR/corrupt-order.json" \
    || fail "corrupt order did not report STORED_STATE_INVALID: $(cat "$TMP_DIR/corrupt-order.json")"

degraded=$(maintenance expire-reservations)
grep -q 'MAINTENANCE_OK command=expire-reservations .* failed=1$' "$TMP_DIR/expire-reservations.log" \
    || fail "sweep did not report exactly one failure: $(cat "$TMP_DIR/expire-reservations.log")"
grep -q 'violates a domain invariant' "$TMP_DIR/expire-reservations.log" \
    || fail "sweep did not name the unreadable row: $(cat "$TMP_DIR/expire-reservations.log")"
note "degraded sweep affected=${degraded:-0}"
[ "${degraded:-0}" -ge 1 ] || fail "degraded sweep applied to $degraded orders, expected >= 1"

api GET "/api/v1/orders/$ORDER_C" > "$TMP_DIR/order-c.json"
grep -q '"status":"CANCELLED"' "$TMP_DIR/order-c.json" \
    || fail "a corrupt row blocked the healthy colleague: $(cat "$TMP_DIR/order-c.json")"

printf '%s\n' '=== repairing the row lets the same sweep finish and the constraint go back on ==='
# Still expired, now consistent: created 2h ago, deadline 1h ago.
sql "UPDATE orders
        SET updated_at = NOW(6) - INTERVAL 1 HOUR,
            expires_at = NOW(6) - INTERVAL 1 HOUR
      WHERE id = '$ORDER_A'" > /dev/null
sql 'ALTER TABLE orders ALTER CHECK chk_orders_reserved_expiry ENFORCED' > /dev/null
repaired=$(maintenance expire-reservations)
grep -q 'MAINTENANCE_OK command=expire-reservations .* failed=0$' "$TMP_DIR/expire-reservations.log" \
    || fail "repaired sweep still reports failures: $(cat "$TMP_DIR/expire-reservations.log")"
[ "${repaired:-0}" -ge 1 ] || fail "repaired sweep applied to $repaired orders, expected >= 1"
api GET "/api/v1/orders/$ORDER_A" > "$TMP_DIR/order-a.json"
grep -q '"status":"CANCELLED"' "$TMP_DIR/order-a.json" \
    || fail "repaired order was not cancelled: $(cat "$TMP_DIR/order-a.json")"
assert_ledger 5 0

curl -sS -o /dev/null -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/auth/token" || true
TOKEN=''
summary=$(printf 'archive_rows=%s archived_order_b=%s expired=%s degraded=%s repaired=%s' \
    "$archive_rows" "$order_b_archived" "$expired_summary" "${degraded:-0}" "${repaired:-0}")
printf '\nMAINTENANCE CHECK OK: %s\n' "$summary"
# ::notice:: surfaces the drill's outcome as a check annotation: raw job logs are often unreachable
# from outside CI, and "the drill passed" should not require downloading a log to confirm.
printf '::notice title=maintenance drill::MAINTENANCE CHECK OK %s\n' "$summary"

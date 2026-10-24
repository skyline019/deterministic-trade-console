/**
 * Write-path latency probe (place order) with explicit p99 threshold.
 * Correctness under contention remains scripts/contend.sh — not this file.
 *
 * Stay under default API_ATTEMPT_LIMIT=300/min (~5 rps): prefer 1 VU + THINK≥0.25s.
 * For higher VU, raise API_ATTEMPT_LIMIT on the app for the probe window only.
 *
 *   k6 run -e BASE_URL=http://127.0.0.1:8080 -e PASSWORD=local-admin-only \
 *     -e P99_MS=500 -e VUS=1 -e DURATION=45s -e THINK=0.3 scripts/k6-p99-write.js
 */
import http from 'k6/http'
import { check, sleep } from 'k6'

const P99_MS = Number(__ENV.P99_MS || 500)

export const options = {
  scenarios: {
    writes: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 1),
      duration: __ENV.DURATION || '45s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: [`p(99)<${P99_MS}`],
    checks: ['rate>0.97'],
  },
}

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080'
const USER = __ENV.USERNAME || 'admin'
const PASS = __ENV.PASSWORD || 'local-admin-only'

export function setup() {
  const health = http.get(`${BASE}/actuator/health`)
  check(health, { 'health UP': (r) => r.status === 200 })
  const res = http.post(
    `${BASE}/api/v1/auth/token`,
    JSON.stringify({ username: USER, password: PASS, delivery: 'bearer' }),
    { headers: { 'Content-Type': 'application/json' } },
  )
  check(res, { 'login 200': (r) => r.status === 200 })
  const token = res.json('accessToken')
  const sku = `K6W-${Date.now()}`
  const stock = Number(__ENV.SEED_STOCK || 50000)
  const inv = http.post(
    `${BASE}/api/v1/inventory`,
    JSON.stringify({ sku, initialStock: stock, reason: 'k6 write seed' }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': `k6w-inv-${sku}`,
      },
    },
  )
  check(inv, { 'seed inventory 2xx': (r) => r.status === 200 || r.status === 201 })
  return { token, sku }
}

export default function (data) {
  const key = `k6w-ord-${__VU}-${__ITER}-${Date.now()}`
  const res = http.post(
    `${BASE}/api/v1/orders`,
    JSON.stringify({ sku: data.sku, quantity: 1 }),
    {
      headers: {
        Authorization: `Bearer ${data.token}`,
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'Idempotency-Key': key,
      },
    },
  )
  check(res, {
    'place 2xx': (r) => r.status === 200 || r.status === 201,
  })
  sleep(Number(__ENV.THINK || 0.3))
}

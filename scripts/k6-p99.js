/**
 * Read-path latency probe with explicit p99 threshold.
 * Correctness under contention remains scripts/contend.sh + IT — not this file.
 *
 *   k6 run -e BASE_URL=http://127.0.0.1:8080 -e PASSWORD=local-admin-only -e P99_MS=250 scripts/k6-p99.js
 */
import http from 'k6/http'
import { check, sleep } from 'k6'

const P99_MS = Number(__ENV.P99_MS || 250)

export const options = {
  scenarios: {
    reads: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '45s',
    },
  },
  // Always print p(99) so verification.md can cite the exact percentile, not only the threshold gate.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: [`p(99)<${P99_MS}`],
    checks: ['rate>0.99'],
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
    JSON.stringify({ username: USER, password: PASS }),
    { headers: { 'Content-Type': 'application/json' } },
  )
  check(res, { 'login 200': (r) => r.status === 200 })
  return { token: res.json('accessToken') }
}

export default function (data) {
  const headers = {
    Authorization: `Bearer ${data.token}`,
    Accept: 'application/json',
  }
  const dash = http.get(`${BASE}/api/v1/dashboard`, { headers })
  check(dash, { 'dashboard 200': (r) => r.status === 200 })
  const inv = http.get(`${BASE}/api/v1/inventory`, { headers })
  check(inv, { 'inventory 200': (r) => r.status === 200 })
  const orders = http.get(`${BASE}/api/v1/orders?limit=30`, { headers })
  check(orders, { 'orders 200': (r) => r.status === 200 })
  sleep(Number(__ENV.THINK || 0.05))
}

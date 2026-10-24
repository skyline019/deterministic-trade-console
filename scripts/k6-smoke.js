/**
 * Optional throughput probe against a running Boot instance.
 * Not a certified latency/QPS benchmark — see docs/review.md.
 *
 *   k6 run -e BASE_URL=http://127.0.0.1:8080 -e PASSWORD=local-admin-only scripts/k6-smoke.js
 */
import http from 'k6/http'
import { check, sleep } from 'k6'

export const options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
}

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080'
const USER = __ENV.USERNAME || 'admin'
const PASS = __ENV.PASSWORD || 'local-admin-only'

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/token`, JSON.stringify({
    username: USER,
    password: PASS,
  }), { headers: { 'Content-Type': 'application/json' } })
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
  sleep(0.2)
}

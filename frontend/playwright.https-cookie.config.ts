import { defineConfig } from '@playwright/test'

/**
 * Cookie-session E2E against Caddy TLS (:8443).
 * Requires: docker compose up (with caddy), TRADE_COOKIE_SECURE=true, trusted/ignored internal CA.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'https-cookie.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-https-cookie', open: 'never' }]],
  use: {
    baseURL: process.env.HTTPS_COOKIE_BASE_URL ?? 'https://localhost:8443',
    headless: true,
    ignoreHTTPSErrors: true,
    viewport: { width: 1440, height: 1000 },
    actionTimeout: 15_000,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
})

import { defineConfig } from '@playwright/test'

/** Same-origin E2E against Compose Boot (static UI + /api on :8080). No Vite demo server. */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'same-origin.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-same-origin', open: 'never' }]],
  use: {
    baseURL: process.env.SAME_ORIGIN_BASE_URL ?? 'http://127.0.0.1:8080',
    headless: true,
    viewport: { width: 1440, height: 1000 },
    actionTimeout: 15_000,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
})

/**
 * Opt-in config for sandboxes/CI runners where the Playwright browser CDN is unreachable:
 * point CHROMIUM_EXECUTABLE at a locally available Chromium/Chrome build and run
 * `npx playwright test -c playwright.local.config.ts`.
 * The default playwright.config.ts (bundled browser) stays untouched.
 */
import base from './playwright.config'
import { defineConfig } from '@playwright/test'

const executablePath = process.env.CHROMIUM_EXECUTABLE

export default defineConfig({
  ...base,
  use: {
    ...base.use,
    ...(executablePath
      ? { launchOptions: { executablePath, args: ['--no-sandbox', '--disable-dev-shm-usage'] } }
      : {}),
  },
})

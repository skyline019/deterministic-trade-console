import { expect, test, type Page } from '@playwright/test'

const ADMIN_PASSWORD = process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'local-admin-only'

async function login(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByLabel('用户名').fill('admin')
  await page.getByLabel('密码').fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: '验证并进入' }).click()
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible()
}

test('HTTPS cookie login keeps session secret out of JS-readable storage', async ({ page }) => {
  const loginResponse = page.waitForResponse(
    (res) => res.url().includes('/api/v1/auth/token') && res.request().method() === 'POST',
  )
  await login(page)
  const res = await loginResponse
  expect(res.status()).toBe(200)
  const body = await res.json()
  expect(body.tokenType).toBe('Cookie')
  expect(body.accessToken === undefined || body.accessToken === null || body.accessToken === '').toBeTruthy()

  const cookies = await page.context().cookies()
  const session = cookies.find((c) => c.name === '__Host-TradeSession')
  const xsrf = cookies.find((c) => c.name === 'TradeXsrf')
  expect(session, 'HttpOnly session cookie').toBeTruthy()
  expect(session?.httpOnly).toBe(true)
  expect(session?.secure).toBe(true)
  expect(xsrf, 'readable CSRF cookie').toBeTruthy()
  expect(xsrf?.httpOnly).toBe(false)

  const jsVisible = await page.evaluate(() => document.cookie)
  expect(jsVisible).toContain('TradeXsrf=')
  expect(jsVisible).not.toContain('__Host-TradeSession=')

  const stored = await page.evaluate(() => sessionStorage.getItem('deterministic-trade.session.v2'))
  if (stored) {
    const parsed = JSON.parse(stored) as { accessToken?: string; authMode?: string }
    expect(parsed.accessToken ?? '').toBe('')
    expect(parsed.authMode === 'cookie' || parsed.authMode === undefined).toBeTruthy()
  }

  await page.getByRole('button', { name: '注销', exact: true }).click()
  await expect(page.getByRole('heading', { name: '进入交易控制台' })).toBeVisible()
})

test('cookie write path rejects missing CSRF and accepts matching double-submit', async ({ page, request }) => {
  await login(page)
  const cookies = await page.context().cookies()
  const session = cookies.find((c) => c.name === '__Host-TradeSession')?.value
  const xsrf = cookies.find((c) => c.name === 'TradeXsrf')?.value
  expect(session && xsrf).toBeTruthy()

  const denied = await request.post('/api/v1/inventory', {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `csrf-deny-${Date.now()}`,
      Cookie: `__Host-TradeSession=${session}; TradeXsrf=${xsrf}`,
    },
    data: { sku: `CSRF-DENY-${Date.now()}`, initialStock: 1, reason: 'missing xsrf header' },
    failOnStatusCode: false,
  })
  expect(denied.status()).toBe(403)
  expect((await denied.json()).code).toBe('CSRF_REJECTED')

  const sku = `CSRF-OK-${Date.now()}`
  const allowed = await request.post('/api/v1/inventory', {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `csrf-ok-${sku}`,
      'X-XSRF-TOKEN': xsrf!,
      Cookie: `__Host-TradeSession=${session}; TradeXsrf=${xsrf}`,
    },
    data: { sku, initialStock: 2, reason: 'double-submit ok' },
  })
  expect([200, 201]).toContain(allowed.status())
})

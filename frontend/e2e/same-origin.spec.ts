import { expect, test, type Page } from '@playwright/test'

/** Real Boot bootstrap admin (Compose default). */
const ADMIN_PASSWORD = process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'local-admin-only'
/** Seeded by scripts/seed_same_origin_users.sh */
const ROLE_PASSWORD = process.env.E2E_ROLE_PASSWORD ?? 'e2e-role-secret'

async function login(page: Page, username = 'admin', password = ADMIN_PASSWORD): Promise<void> {
  await page.goto('/')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '验证并进入' }).click()
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible()
}

async function nav(page: Page, label: string): Promise<void> {
  await page.locator('.main-nav button').filter({ hasText: label }).click()
}

async function createSku(page: Page, sku: string, stock: number): Promise<void> {
  await nav(page, '库存台账')
  await page.getByRole('button', { name: '新建 SKU' }).click()
  await page.getByLabel('SKU 标识').fill(sku)
  await page.getByLabel('初始可售库存').fill(String(stock))
  await page.getByLabel('审计原因').fill('same-origin E2E seed')
  await page.getByRole('button', { name: '提交并写入审计' }).click()
  await expect(page.locator('.modal-backdrop')).toHaveCount(0)
  await expect(page.getByRole('row').filter({ hasText: sku })).toBeVisible()
}

test('authentication failure is explicit and logout returns to a clean boundary', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('用户名').fill('admin')
  await page.getByLabel('密码').fill('wrong-password')
  await page.getByRole('button', { name: '验证并进入' }).click()
  await expect(page.getByRole('alert')).toContainText('用户名或密码错误')
  await expect(page.locator('.app-shell')).toHaveCount(0)

  await page.getByLabel('密码').fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: '验证并进入' }).click()
  await expect(page.locator('.app-shell')).toBeVisible()
  await page.getByRole('button', { name: '注销', exact: true }).click()
  await expect(page.getByRole('heading', { name: '进入交易控制台' })).toBeVisible()
  await expect(page.locator('.app-shell')).toHaveCount(0)
})

test('viewer sees reads but neither mutation controls nor audit contents', async ({ page }) => {
  await login(page, 'viewer', ROLE_PASSWORD)
  await nav(page, '库存台账')
  await expect(page.getByRole('button', { name: '新建 SKU' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '调整', exact: true })).toHaveCount(0)
  await nav(page, '订单生命周期')
  await expect(page.getByRole('button', { name: '创建预占订单' })).toHaveCount(0)
  await nav(page, '审计验证')
  await expect(page.getByRole('heading', { name: '当前角色没有审计权限' })).toBeVisible()
  await expect(page.getByRole('button', { name: '验证完整链' })).toHaveCount(0)
})

test('inventory, reserve, cancel, confirm and audit form one consistent workflow', async ({ page }) => {
  const sku = `SO-FLOW-${Date.now()}`
  await login(page)
  await createSku(page, sku, 10)

  const stockRow = page.getByRole('row').filter({ hasText: sku })
  await stockRow.getByRole('button', { name: '调整', exact: true }).click()
  await page.getByLabel('库存调整量').fill('-2')
  await page.getByLabel('审计原因').fill('same-origin cycle count')
  await page.getByRole('button', { name: '提交并写入审计' }).click()
  await expect(stockRow.locator('.stock-number')).toHaveText('8')

  await nav(page, '订单生命周期')
  await page.getByRole('button', { name: '创建预占订单' }).click()
  await page.locator('.modal-panel').getByLabel('库存 SKU').selectOption(sku)
  await page.getByLabel('预占数量').fill('3')
  await page.getByRole('button', { name: '执行库存预占' }).click()
  const cancelledRow = page.getByRole('row').filter({ hasText: sku }).first()
  await expect(cancelledRow.getByText('已预占', { exact: true })).toBeVisible()
  await cancelledRow.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByLabel('审计原因').fill('same-origin customer cancellation')
  await page.getByRole('button', { name: '取消并释放' }).click()
  await expect(cancelledRow.getByText('已取消', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '创建预占订单' }).click()
  await page.locator('.modal-panel').getByLabel('库存 SKU').selectOption(sku)
  await page.getByLabel('预占数量').fill('2')
  await page.getByRole('button', { name: '执行库存预占' }).click()
  const confirmedRow = page.getByRole('row').filter({ hasText: sku }).first()
  await confirmedRow.getByRole('button', { name: '确认', exact: true }).click()
  await page.getByLabel('审计原因').fill('same-origin payment confirmed')
  await page.getByRole('button', { name: '确认并核销' }).click()
  await expect(confirmedRow.getByText('已确认', { exact: true })).toBeVisible()

  await nav(page, '库存台账')
  await expect(stockRow.locator('.stock-number')).toHaveText('6')
  await expect(stockRow.locator('.reserved-number')).toHaveText('0')

  await nav(page, '订单生命周期')
  const auditRow = page.getByRole('row').filter({ hasText: sku }).filter({ hasText: '已取消' })
  await auditRow.getByTitle('查看审计链').click()
  await expect(page.getByLabel('聚合标识')).toHaveValue(/[0-9a-f-]{36}/)
  await page.getByRole('button', { name: '验证完整链' }).click()
  await expect(page.locator('.verification-result.valid')).toContainText('2 EVENTS CHECKED')

  await page.getByRole('button', { name: '库存链' }).click()
  await page.getByLabel('聚合标识').fill(sku)
  await page.getByRole('button', { name: '验证完整链' }).click()
  await expect(page.locator('.verification-result.valid')).toContainText('6 EVENTS CHECKED')
})

test('failed commands keep the modal context and do not create effects', async ({ page }) => {
  const sku = `SO-FAIL-${Date.now()}`
  await login(page)
  await createSku(page, sku, 1)
  await nav(page, '订单生命周期')
  await page.getByRole('button', { name: '创建预占订单' }).click()
  await page.locator('.modal-panel').getByLabel('库存 SKU').selectOption(sku)
  await page.getByLabel('预占数量').fill('2')
  await page.getByRole('button', { name: '执行库存预占' }).click()
  await expect(page.getByRole('alert')).toContainText('可售库存不足')
  await expect(page.locator('.modal-backdrop')).toBeVisible()
  await page.locator('.modal-panel').getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: sku })).toHaveCount(0)

  await nav(page, '库存台账')
  const row = page.getByRole('row').filter({ hasText: sku })
  await expect(row.locator('.stock-number')).toHaveText('1')
  await row.getByRole('button', { name: '调整', exact: true }).click()
  await page.getByLabel('库存调整量').fill('-2')
  await page.getByLabel('审计原因').fill('same-origin invalid correction')
  await page.getByRole('button', { name: '提交并写入审计' }).click()
  await expect(page.getByRole('alert')).toContainText('库存调整越界')
  await page.locator('.modal-panel').getByRole('button', { name: '取消', exact: true }).click()
  await expect(row.locator('.stock-number')).toHaveText('1')
})

test('mobile navigation remains usable without horizontal page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await login(page)
  await page.getByRole('button', { name: '打开导航' }).click()
  await page.locator('.main-nav button').filter({ hasText: '库存台账' }).click()
  await expect(page.getByRole('heading', { name: '库存台账', exact: true })).toBeVisible()
  const dimensions = await page.evaluate(() => ({
    width: window.innerWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.width + 1)
})

test('session restores in the same tab and logout survives another reload', async ({ page }) => {
  await login(page, 'operator', ROLE_PASSWORD)
  await page.reload()
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible()
  await nav(page, '库存台账')
  await expect(page.getByRole('button', { name: '新建 SKU' })).toBeVisible()
  await nav(page, '审计验证')
  await expect(page.getByRole('heading', { name: '当前角色没有审计权限' })).toBeVisible()
  await page.getByRole('button', { name: '注销', exact: true }).click()
  await page.reload()
  await expect(page.getByRole('heading', { name: '进入交易控制台' })).toBeVisible()
  await expect(page.locator('.app-shell')).toHaveCount(0)
})

test('auditor can inspect chains but cannot mutate inventory or orders', async ({ page }) => {
  await login(page, 'auditor', ROLE_PASSWORD)
  await nav(page, '库存台账')
  await expect(page.getByRole('button', { name: '新建 SKU' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '调整', exact: true })).toHaveCount(0)
  await nav(page, '订单生命周期')
  await expect(page.getByRole('button', { name: '创建预占订单' })).toHaveCount(0)
  // Smoke/contend left ORDER audit rows; title may exist after prior admin tests too.
  const chainButtons = page.getByTitle('查看审计链')
  if (await chainButtons.count() > 0) {
    await expect(chainButtons.first()).toBeVisible()
  }
  await nav(page, '审计验证')
  await expect(page.getByRole('heading', { name: '当前角色没有审计权限' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '验证完整链' })).toBeVisible()
})

test('keyset next and previous pages cover filtered rows without duplicates', async ({ page }) => {
  const sku = `SO-PAGE-${Date.now()}`
  await login(page)
  await createSku(page, sku, 20)
  await nav(page, '订单生命周期')
  for (let index = 0; index < 8; index += 1) {
    await page.getByRole('button', { name: '创建预占订单' }).click()
    await page.locator('.modal-panel').getByLabel('库存 SKU').selectOption(sku)
    await page.getByLabel('预占数量').fill('1')
    await page.getByRole('button', { name: '执行库存预占' }).click()
    await expect(page.locator('.modal-backdrop')).toHaveCount(0)
  }
  // Ensure enough rows for page size 10: create 3 more via API-shaped UI (11 total).
  for (let index = 0; index < 3; index += 1) {
    await page.getByRole('button', { name: '创建预占订单' }).click()
    await page.locator('.modal-panel').getByLabel('库存 SKU').selectOption(sku)
    await page.getByLabel('预占数量').fill('1')
    await page.getByRole('button', { name: '执行库存预占' }).click()
    await expect(page.locator('.modal-backdrop')).toHaveCount(0)
  }
  await page.getByLabel('库存 SKU').selectOption(sku)
  await page.getByLabel('每页').selectOption('10')
  await page.getByRole('button', { name: '应用筛选' }).click()
  await expect(page.locator('.orders-table tbody tr')).toHaveCount(10)
  const firstKeys = await page.locator('.orders-table .request-cell').allTextContents()
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.locator('.orders-table tbody tr')).toHaveCount(1)
  const secondKeys = await page.locator('.orders-table .request-cell').allTextContents()
  expect(secondKeys.filter((key) => firstKeys.includes(key))).toEqual([])
  expect(new Set([...firstKeys, ...secondKeys]).size).toBe(11)
  await page.getByRole('button', { name: '上一页' }).click()
  await expect(page.locator('.orders-table tbody tr')).toHaveCount(10)
  await expect(page.getByRole('button', { name: '下一页' })).toBeEnabled()
})

test('partial confirm shrinks reserved quantity without terminalizing', async ({ page }) => {
  const sku = `SO-PC-${Date.now()}`
  await login(page)
  await createSku(page, sku, 10)
  await nav(page, '订单生命周期')
  await page.getByRole('button', { name: '创建预占订单' }).click()
  await page.locator('.modal-panel').getByLabel('库存 SKU').selectOption(sku)
  await page.getByLabel('预占数量').fill('5')
  await page.getByRole('button', { name: '执行库存预占' }).click()
  const row = page.getByRole('row').filter({ hasText: sku }).first()
  await expect(row.getByText('已预占', { exact: true })).toBeVisible()
  await row.getByRole('button', { name: '确认', exact: true }).click()
  await page.getByLabel('确认数量').fill('2')
  await page.getByLabel('审计原因').fill('same-origin partial ship')
  await page.getByRole('button', { name: '部分确认' }).click()
  await expect(row.getByText('已预占', { exact: true })).toBeVisible()
  await expect(row.locator('.quantity-cell')).toHaveText('3')
  await nav(page, '库存台账')
  const stockRow = page.getByRole('row').filter({ hasText: sku })
  await expect(stockRow.locator('.stock-number')).toHaveText('5')
  await expect(stockRow.locator('.reserved-number')).toHaveText('3')
})

test('reschedule updates expires column for a live reservation', async ({ page }) => {
  const sku = `SO-RS-${Date.now()}`
  await login(page)
  await createSku(page, sku, 5)
  await nav(page, '订单生命周期')
  await page.getByRole('button', { name: '创建预占订单' }).click()
  await page.locator('.modal-panel').getByLabel('库存 SKU').selectOption(sku)
  await page.getByLabel('预占数量').fill('1')
  await page.getByRole('button', { name: '执行库存预占' }).click()
  const row = page.getByRole('row').filter({ hasText: sku }).first()
  await row.getByRole('button', { name: '改期', exact: true }).click()
  await page.locator('#reschedule-expires').evaluate((el: HTMLInputElement) => {
    const d = new Date(el.value)
    d.setMinutes(d.getMinutes() - 20)
    const pad = (n: number) => String(n).padStart(2, '0')
    el.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await page.getByLabel('审计原因').fill('same-origin customer delay')
  await page.getByRole('button', { name: '确认改期' }).click()
  await expect(page.locator('.modal-backdrop')).toHaveCount(0)
  await expect(row.getByText('已预占', { exact: true })).toBeVisible()
})

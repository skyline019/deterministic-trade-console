import { describe, expect, it } from 'vitest'
import { DemoGateway } from './demo'
import { ApiError, type Session } from './types'

async function admin(gateway: DemoGateway): Promise<Session> {
  return gateway.login('admin', 'deterministic-demo')
}

describe('DemoGateway command contract', () => {
  it('replays one order without a second reservation and releases on cancellation', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.createInventory(session, { sku: 'TEST-SKU', initialStock: 10, reason: 'unit test seed' }, 'create-test')

    const placed = await gateway.placeOrder(session, { sku: 'TEST-SKU', quantity: 3 }, 'place-test')
    const replay = await gateway.placeOrder(session, { sku: 'TEST-SKU', quantity: 3 }, 'place-test')
    expect(placed.replayed).toBe(false)
    expect(replay.replayed).toBe(true)
    expect(replay.value.id).toBe(placed.value.id)
    expect((await gateway.listInventory(session)).find((item) => item.sku === 'TEST-SKU')).toMatchObject({ available: 7, reserved: 3 })

    const cancelled = await gateway.transitionOrder(session, placed.value.id, 'CANCELLED', 'test cancellation', 'cancel-test')
    expect(cancelled.value.status).toBe('CANCELLED')
    expect((await gateway.listInventory(session)).find((item) => item.sku === 'TEST-SKU')).toMatchObject({ available: 10, reserved: 0 })
    expect((await gateway.verifyAudit(session, 'ORDER', placed.value.id))).toMatchObject({ valid: true, checkedEvents: 2 })
  })

  it('rejects a reused key with different content', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.placeOrder(session, { sku: 'SKU-CORE-01', quantity: 1 }, 'same-key')
    await expect(gateway.placeOrder(session, { sku: 'SKU-CORE-01', quantity: 2 }, 'same-key'))
      .rejects.toMatchObject({ code: 'IDEMPOTENCY_CONFLICT', status: 409 })
  })

  it('enforces write and audit roles independently', async () => {
    const gateway = new DemoGateway()
    const viewer = await gateway.login('viewer', 'deterministic-demo')
    await expect(gateway.placeOrder(viewer, { sku: 'SKU-CORE-01', quantity: 1 }, 'viewer-write'))
      .rejects.toMatchObject({ code: 'ACCESS_DENIED', status: 403 })
    await expect(gateway.auditEvents(viewer, 'ORDER', '00000000-0000-0000-0000-000000000006'))
      .rejects.toBeInstanceOf(ApiError)

    const auditor = await gateway.login('auditor', 'deterministic-demo')
    expect((await gateway.auditEvents(auditor, 'ORDER', '00000000-0000-0000-0000-000000000006')).length).toBeGreaterThan(0)
  })

  it('logs out immediately and uses generic password failure', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.logout(session)
    await expect(gateway.dashboard(session)).rejects.toMatchObject({ status: 401 })
    await expect(gateway.login('admin', 'wrong')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
  })

  it('validates command shapes before receipt lookup and rejects malformed keys', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.placeOrder(session, { sku: 'SKU-CORE-01', quantity: 1 }, 'validation-replay')
    await expect(gateway.placeOrder(session, { sku: 'SKU-CORE-01', quantity: 0 }, 'validation-replay'))
      .rejects.toMatchObject({ status: 400, code: 'INVALID_REQUEST' })
    await expect(gateway.placeOrder(session, { sku: 'SKU-CORE-01', quantity: 1 }, ' bad key '))
      .rejects.toMatchObject({ status: 400, code: 'INVALID_REQUEST' })
    await expect(gateway.createInventory(session, { sku: 'bad sku', initialStock: 1, reason: 'bad sku' }, 'bad-sku'))
      .rejects.toMatchObject({ status: 400, code: 'INVALID_REQUEST' })
  })

  it('uses the same atomic missing-stock failure semantics as the HTTP service', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await expect(gateway.placeOrder(session, { sku: 'SKU-MISSING', quantity: 1 }, 'missing-place'))
      .rejects.toMatchObject({ status: 409, code: 'OUT_OF_STOCK' })
    await expect(gateway.adjustInventory(session, 'SKU-MISSING', { delta: 1, reason: 'missing stock' }, 'missing-adjust'))
      .rejects.toMatchObject({ status: 409, code: 'STOCK_ADJUSTMENT_REJECTED' })
  })

  it('paginates every matching order once and rejects invalid windows', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.createInventory(session, { sku: 'PAGE-SKU', initialStock: 20, reason: 'pagination seed' }, 'page-seed')
    const ids = new Set<string>()
    for (let index = 0; index < 12; index += 1) {
      const result = await gateway.placeOrder(session, { sku: 'PAGE-SKU', quantity: 1 }, `page-${index}`)
      ids.add(result.value.id)
    }
    const first = await gateway.listOrders(session, { sku: 'PAGE-SKU', limit: 5 })
    const second = await gateway.listOrders(session, { sku: 'PAGE-SKU', limit: 5, cursor: first.nextCursor ?? undefined })
    const third = await gateway.listOrders(session, { sku: 'PAGE-SKU', limit: 5, cursor: second.nextCursor ?? undefined })
    const paged = [...first.items, ...second.items, ...third.items]
    expect(first.items).toHaveLength(5)
    expect(second.items).toHaveLength(5)
    expect(third.items).toHaveLength(2)
    expect(third.nextCursor).toBeNull()
    expect(new Set(paged.map((order) => order.id))).toEqual(ids)
    await expect(gateway.listOrders(session, { limit: 101 })).rejects.toMatchObject({ status: 400 })
    await expect(gateway.listOrders(session, { cursor: 'invalid' })).rejects.toMatchObject({ status: 400 })
  })

  it('confirmation consumes but never refills stock and terminal replay stays stable', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.createInventory(session, { sku: 'CONFIRM-SKU', initialStock: 10, reason: 'confirmation seed' }, 'confirm-seed')
    const placed = await gateway.placeOrder(session, { sku: 'CONFIRM-SKU', quantity: 3 }, 'confirm-place')
    const confirmed = await gateway.transitionOrder(session, placed.value.id, 'CONFIRMED', 'payment confirmed', 'confirm-once')
    const replay = await gateway.transitionOrder(session, placed.value.id, 'CONFIRMED', 'payment confirmed', 'confirm-once')
    expect(confirmed.value.status).toBe('CONFIRMED')
    expect(replay).toMatchObject({ replayed: true, value: { version: 1, status: 'CONFIRMED' } })
    await expect(gateway.transitionOrder(session, placed.value.id, 'CANCELLED', 'too late', 'cancel-too-late'))
      .rejects.toMatchObject({ status: 409, code: 'ORDER_STATE_CONFLICT' })
    expect((await gateway.listInventory(session)).find((item) => item.sku === 'CONFIRM-SKU'))
      .toMatchObject({ available: 7, reserved: 0 })
    expect((await gateway.verifyAudit(session, 'ORDER', placed.value.id)).checkedEvents).toBe(2)
  })

  it('reschedule updates expiresAt within demo TTL without touching stock', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.createInventory(session, { sku: 'RS-SKU', initialStock: 5, reason: 'reschedule seed' }, 'rs-seed')
    const placed = await gateway.placeOrder(session, { sku: 'RS-SKU', quantity: 2 }, 'rs-place')
    const baseMs = Date.parse(placed.value.updatedAt)
    const next = new Date(baseMs + 30 * 60_000).toISOString()
    const moved = await gateway.rescheduleOrder(session, placed.value.id, next, 'extend thirty', 'rs-once')
    expect(moved.value.expiresAt).toBe(next)
    expect(moved.value.status).toBe('RESERVED')
    expect((await gateway.listInventory(session)).find((item) => item.sku === 'RS-SKU'))
      .toMatchObject({ available: 3, reserved: 2 })
    const replay = await gateway.rescheduleOrder(session, placed.value.id, next, 'extend thirty', 'rs-once')
    expect(replay).toMatchObject({ replayed: true, value: { expiresAt: next } })
    await expect(gateway.rescheduleOrder(session, placed.value.id, new Date(baseMs + 45 * 60_000).toISOString(), 'extend thirty', 'rs-once'))
      .rejects.toMatchObject({ status: 409, code: 'IDEMPOTENCY_CONFLICT' })
    await expect(gateway.rescheduleOrder(session, placed.value.id, new Date(baseMs - 60_000).toISOString(), 'past', 'rs-past'))
      .rejects.toMatchObject({ status: 400 })
    await expect(gateway.rescheduleOrder(session, placed.value.id, new Date(baseMs + 2 * 60 * 60_000).toISOString(), 'over', 'rs-over'))
      .rejects.toMatchObject({ status: 400 })
  })

  it('partial confirmation shrinks reserved quantity without terminalizing', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.createInventory(session, { sku: 'PARTIAL-SKU', initialStock: 10, reason: 'partial seed' }, 'partial-seed')
    const placed = await gateway.placeOrder(session, { sku: 'PARTIAL-SKU', quantity: 5 }, 'partial-place')
    const partial = await gateway.transitionOrder(session, placed.value.id, 'CONFIRMED', 'ship two', 'partial-once', 2)
    expect(partial.value).toMatchObject({ status: 'RESERVED', quantity: 3 })
    expect((await gateway.listInventory(session)).find((item) => item.sku === 'PARTIAL-SKU'))
      .toMatchObject({ available: 5, reserved: 3 })
    await expect(gateway.transitionOrder(session, placed.value.id, 'CONFIRMED', 'ship two', 'partial-once', 3))
      .rejects.toMatchObject({ status: 409, code: 'IDEMPOTENCY_CONFLICT' })
    const replay = await gateway.transitionOrder(session, placed.value.id, 'CONFIRMED', 'ship two', 'partial-once', 2)
    expect(replay).toMatchObject({ replayed: true, value: { quantity: 3, status: 'RESERVED' } })
    const rest = await gateway.transitionOrder(session, placed.value.id, 'CONFIRMED', 'ship rest', 'partial-rest', 3)
    expect(rest.value.status).toBe('CONFIRMED')
    expect((await gateway.listInventory(session)).find((item) => item.sku === 'PARTIAL-SKU'))
      .toMatchObject({ available: 5, reserved: 0 })
  })

  it('restores a valid page session without resurrecting a token revoked in that page', async () => {
    const original = new DemoGateway()
    const session = await admin(original)
    const restored = new DemoGateway()
    expect((await restored.dashboard(session)).totalOrders).toBeGreaterThan(0)
    await restored.logout(session)
    await expect(restored.dashboard(session)).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' })
    await original.logout(session)
    await expect(original.dashboard(session)).rejects.toMatchObject({ status: 401 })
  })

  it('capacity checks include reservations and failed adjustments leave no audit effect', async () => {
    const gateway = new DemoGateway()
    const session = await admin(gateway)
    await gateway.createInventory(session, { sku: 'CAPACITY-SKU', initialStock: 5, reason: 'capacity seed' }, 'capacity-seed')
    await gateway.placeOrder(session, { sku: 'CAPACITY-SKU', quantity: 2 }, 'capacity-place')
    await expect(gateway.adjustInventory(session, 'CAPACITY-SKU', { delta: 99_999_999, reason: 'overflow' }, 'capacity-overflow'))
      .rejects.toMatchObject({ status: 409, code: 'STOCK_ADJUSTMENT_REJECTED' })
    await expect(gateway.adjustInventory(session, 'CAPACITY-SKU', { delta: -4, reason: 'below zero' }, 'capacity-negative'))
      .rejects.toMatchObject({ status: 409, code: 'STOCK_ADJUSTMENT_REJECTED' })
    expect((await gateway.listInventory(session)).find((item) => item.sku === 'CAPACITY-SKU'))
      .toMatchObject({ available: 3, reserved: 2, version: 1 })
    expect((await gateway.auditEvents(session, 'INVENTORY', 'CAPACITY-SKU'))).toHaveLength(2)
  })
})

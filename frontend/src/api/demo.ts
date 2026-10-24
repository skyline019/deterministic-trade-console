import {
  ApiError,
  type Actor,
  type AggregateType,
  type AuditEvent,
  type AuditVerification,
  type DashboardStats,
  type Inventory,
  type InventoryOperation,
  type Mutation,
  type Order,
  type OrderPage,
  type OrderQuery,
  type OrderStatus,
  type Session,
  type TradeGateway,
} from './types'

const DEMO_PASSWORD = 'deterministic-demo'
const MAX_STOCK = 100_000_000
const MAX_ORDER_QUANTITY = 10_000
const DEMO_ROLES: Record<string, Actor['roles']> = {
  admin: ['ADMIN'],
  operator: ['OPERATOR'],
  viewer: ['VIEWER'],
  auditor: ['AUDITOR'],
}

function demoActor(username: string): Actor | null {
  if (!DEMO_ROLES[username]) return null
  return {
    id: `00000000-0000-0000-0000-00000000000${Object.keys(DEMO_ROLES).indexOf(username) + 1}`,
    username,
    roles: DEMO_ROLES[username],
  }
}

interface OrderReceipt { sku: string; quantity: number; orderId: string }
interface TransitionReceipt { orderId: string; target: OrderStatus; reason: string; effectQuantity: number }
interface RescheduleReceipt { orderId: string; expiresAt: string; reason: string }
interface InventoryReceipt { fingerprint: string; operation: InventoryOperation }

const DEMO_RESERVATION_TTL_MS = 60 * 60 * 1000

function clone<T>(value: T): T {
  return structuredClone(value)
}

export class DemoGateway implements TradeGateway {
  private inventory: Inventory[] = [
    { sku: 'SKU-CORE-01', available: 116, reserved: 4, version: 8, updatedAt: '2026-01-01T08:30:00Z' },
    { sku: 'SKU-EDGE-02', available: 39, reserved: 3, version: 5, updatedAt: '2026-01-01T08:20:00Z' },
    { sku: 'SKU-RESERVE-03', available: 72, reserved: 0, version: 2, updatedAt: '2026-01-01T08:10:00Z' },
  ]

  private orders: Order[] = [
    { id: '00000000-0000-0000-0000-000000000006', requestId: 'seed-order-006', sku: 'SKU-CORE-01', quantity: 2, status: 'RESERVED', createdAt: '2026-01-01T08:30:00Z', updatedAt: '2026-01-01T08:30:00Z', expiresAt: '2099-01-01T08:30:00Z', version: 0 },
    { id: '00000000-0000-0000-0000-000000000005', requestId: 'seed-order-005', sku: 'SKU-EDGE-02', quantity: 3, status: 'RESERVED', createdAt: '2026-01-01T08:20:00Z', updatedAt: '2026-01-01T08:20:00Z', expiresAt: '2099-01-01T08:20:00Z', version: 0 },
    { id: '00000000-0000-0000-0000-000000000004', requestId: 'seed-order-004', sku: 'SKU-CORE-01', quantity: 2, status: 'CONFIRMED', createdAt: '2026-01-01T08:00:00Z', updatedAt: '2026-01-01T08:05:00Z', expiresAt: '2026-01-01T09:00:00Z', version: 1 },
    { id: '00000000-0000-0000-0000-000000000003', requestId: 'seed-order-003', sku: 'SKU-RESERVE-03', quantity: 8, status: 'CANCELLED', createdAt: '2026-01-01T07:40:00Z', updatedAt: '2026-01-01T07:45:00Z', expiresAt: '2026-01-01T08:40:00Z', version: 1 },
    { id: '00000000-0000-0000-0000-000000000002', requestId: 'seed-order-002', sku: 'SKU-CORE-01', quantity: 5, status: 'CONFIRMED', createdAt: '2026-01-01T07:20:00Z', updatedAt: '2026-01-01T07:25:00Z', expiresAt: '2026-01-01T08:20:00Z', version: 1 },
    { id: '00000000-0000-0000-0000-000000000001', requestId: 'seed-order-001', sku: 'SKU-EDGE-02', quantity: 1, status: 'CANCELLED', createdAt: '2026-01-01T07:00:00Z', updatedAt: '2026-01-01T07:10:00Z', expiresAt: '2026-01-01T08:00:00Z', version: 1 },
  ]

  private audits: AuditEvent[] = []
  private sessions = new Map<string, Session>()
  private revokedTokens = new Set<string>()
  private orderReceipts = new Map<string, OrderReceipt>()
  private transitionReceipts = new Map<string, TransitionReceipt>()
  private rescheduleReceipts = new Map<string, RescheduleReceipt>()
  private inventoryReceipts = new Map<string, InventoryReceipt>()
  private tick = 0

  constructor() {
    for (const order of [...this.orders].reverse()) {
      this.appendAudit('ORDER', order.id, order.status === 'RESERVED' ? 'ORDER_RESERVED' : `ORDER_${order.status}`,
        this.systemActor(), order.requestId, { sku: order.sku, quantity: String(order.quantity), status: order.status }, order.updatedAt)
    }
    for (const item of this.inventory) {
      this.appendAudit('INVENTORY', item.sku, 'STOCK_CREATED', this.systemActor(), `seed-${item.sku}`,
        { available: String(item.available + item.reserved), reason: 'deterministic seed' }, item.updatedAt)
    }
  }

  async login(username: string, password: string): Promise<Session> {
    const normalized = username.trim().toLowerCase()
    const actor = demoActor(normalized)
    if (password !== DEMO_PASSWORD || !actor) {
      throw new ApiError('用户名或密码错误', 401, 'UNAUTHENTICATED')
    }
    const session: Session = {
      accessToken: `demo-${normalized}-not-a-production-credential`,
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      actor,
    }
    this.revokedTokens.delete(session.accessToken)
    this.sessions.set(session.accessToken, session)
    return clone(session)
  }

  async logout(session: Session): Promise<void> {
    this.sessions.delete(session.accessToken)
    this.revokedTokens.add(session.accessToken)
  }

  async dashboard(session: Session): Promise<DashboardStats> {
    this.requireActor(session)
    return {
      skuCount: this.inventory.length,
      availableStock: this.inventory.reduce((sum, item) => sum + item.available, 0),
      reservedStock: this.inventory.reduce((sum, item) => sum + item.reserved, 0),
      totalOrders: this.orders.length,
      reservedOrders: this.orders.filter((order) => order.status === 'RESERVED').length,
      confirmedOrders: this.orders.filter((order) => order.status === 'CONFIRMED').length,
      cancelledOrders: this.orders.filter((order) => order.status === 'CANCELLED').length,
    }
  }

  async listInventory(session: Session): Promise<Inventory[]> {
    this.requireActor(session)
    return clone([...this.inventory].sort((a, b) => a.sku.localeCompare(b.sku)))
  }

  async createInventory(session: Session, input: { sku: string; initialStock: number; reason: string }, key: string): Promise<Mutation<InventoryOperation>> {
    this.validateKey(key)
    this.validateSku(input.sku)
    if (!Number.isInteger(input.initialStock) || input.initialStock < 0 || input.initialStock > MAX_STOCK) throw new ApiError('初始库存超出边界', 400, 'INVALID_REQUEST')
    const actor = this.requireWrite(session)
    this.validateReason(input.reason)
    const fingerprint = JSON.stringify({ kind: 'CREATE', ...input })
    const replay = this.inventoryReceipts.get(key)
    if (replay) {
      if (replay.fingerprint !== fingerprint) throw new ApiError('幂等键已用于不同请求', 409, 'IDEMPOTENCY_CONFLICT')
      return { value: clone(replay.operation), replayed: true }
    }
    if (this.inventory.some((item) => item.sku === input.sku)) throw new ApiError('SKU 已存在', 409, 'RESOURCE_CONFLICT')
    const at = this.now()
    const item: Inventory = { sku: input.sku, available: input.initialStock, reserved: 0, version: 0, updatedAt: at }
    this.inventory.push(item)
    const operation: InventoryOperation = {
      requestId: key, sku: item.sku, operation: 'CREATE', delta: input.initialStock,
      reason: input.reason.trim(), resultingAvailable: item.available, actorId: actor.id, createdAt: at, replayed: false,
    }
    this.inventoryReceipts.set(key, { fingerprint, operation })
    this.appendAudit('INVENTORY', item.sku, 'STOCK_CREATED', actor, key,
      { initialStock: String(input.initialStock), reason: input.reason.trim() }, at)
    return { value: clone(operation), replayed: false }
  }

  async adjustInventory(session: Session, sku: string, input: { delta: number; reason: string }, key: string): Promise<Mutation<InventoryOperation>> {
    this.validateKey(key)
    this.validateSku(sku)
    const actor = this.requireWrite(session)
    this.validateReason(input.reason)
    if (!Number.isSafeInteger(input.delta) || input.delta === 0 || input.delta < -2_147_483_648 || input.delta > 2_147_483_647) {
      throw new ApiError('调整量必须是非零 32 位整数', 400, 'INVALID_REQUEST')
    }
    const fingerprint = JSON.stringify({ kind: 'ADJUST', sku, ...input })
    const replay = this.inventoryReceipts.get(key)
    if (replay) {
      if (replay.fingerprint !== fingerprint) throw new ApiError('幂等键已用于不同请求', 409, 'IDEMPOTENCY_CONFLICT')
      return { value: clone(replay.operation), replayed: true }
    }
    const item = this.inventory.find((candidate) => candidate.sku === sku)
    if (!item) throw new ApiError('库存 SKU 不存在或调整越界', 409, 'STOCK_ADJUSTMENT_REJECTED')
    const available = item.available + input.delta
    if (available < 0 || available + item.reserved > MAX_STOCK) throw new ApiError('调整将导致库存越界', 409, 'STOCK_ADJUSTMENT_REJECTED')
    const at = this.now()
    item.available = available
    item.version += 1
    item.updatedAt = at
    const operation: InventoryOperation = {
      requestId: key, sku, operation: 'ADJUST', delta: input.delta,
      reason: input.reason.trim(), resultingAvailable: available, actorId: actor.id, createdAt: at, replayed: false,
    }
    this.inventoryReceipts.set(key, { fingerprint, operation })
    this.appendAudit('INVENTORY', sku, 'STOCK_ADJUSTED', actor, key,
      { delta: String(input.delta), resultingAvailable: String(available), reason: input.reason.trim() }, at)
    return { value: clone(operation), replayed: false }
  }

  async listOrders(session: Session, query: OrderQuery = {}): Promise<OrderPage> {
    this.requireActor(session)
    if (query.sku) this.validateSku(query.sku)
    const limit = query.limit ?? 30
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new ApiError('分页大小必须在 1–100 之间', 400, 'INVALID_REQUEST')
    let offset = 0
    if (query.cursor !== undefined) {
      if (!/^demo:(0|[1-9][0-9]*)$/.test(query.cursor)) throw new ApiError('游标格式无效', 400, 'INVALID_REQUEST')
      offset = Number(query.cursor.slice(5))
      if (!Number.isSafeInteger(offset)) throw new ApiError('游标格式无效', 400, 'INVALID_REQUEST')
    }
    const filtered = this.orders
      .filter((order) => !query.status || order.status === query.status)
      .filter((order) => !query.sku || order.sku === query.sku)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id))
    const items = filtered.slice(offset, offset + limit)
    const nextCursor = offset + limit < filtered.length ? `demo:${offset + limit}` : null
    return { items: clone(items), nextCursor }
  }

  async placeOrder(session: Session, input: { sku: string; quantity: number }, key: string): Promise<Mutation<Order>> {
    this.validateKey(key)
    this.validateSku(input.sku)
    if (!Number.isInteger(input.quantity) || input.quantity < 1 || input.quantity > MAX_ORDER_QUANTITY) throw new ApiError('下单数量超出边界', 400, 'INVALID_REQUEST')
    const actor = this.requireWrite(session)
    const receipt = this.orderReceipts.get(key)
    if (receipt) {
      if (receipt.sku !== input.sku || receipt.quantity !== input.quantity) throw new ApiError('幂等键已用于不同下单内容', 409, 'IDEMPOTENCY_CONFLICT')
      return { value: clone(this.requireOrder(receipt.orderId)), replayed: true }
    }
    const item = this.inventory.find((candidate) => candidate.sku === input.sku)
    if (!item || item.available < input.quantity) throw new ApiError('可售库存不足', 409, 'OUT_OF_STOCK')
    const at = this.now()
    const order: Order = {
      id: this.nextId(), requestId: key, sku: input.sku, quantity: input.quantity,
      status: 'RESERVED', createdAt: at, updatedAt: at,
      expiresAt: new Date(Date.parse(at) + 3_600_000).toISOString(), version: 0,
    }
    item.available -= input.quantity
    item.reserved += input.quantity
    item.version += 1
    item.updatedAt = at
    this.orders.unshift(order)
    this.orderReceipts.set(key, { ...input, orderId: order.id })
    this.appendAudit('INVENTORY', item.sku, 'STOCK_RESERVED', actor, key,
      { orderId: order.id, quantity: String(input.quantity), available: String(item.available), reserved: String(item.reserved) }, at)
    this.appendAudit('ORDER', order.id, 'ORDER_RESERVED', actor, key,
      { sku: order.sku, quantity: String(order.quantity), status: order.status }, at)
    return { value: clone(order), replayed: false }
  }

  async transitionOrder(session: Session, id: string, target: 'CONFIRMED' | 'CANCELLED', reason: string, key: string, quantity?: number): Promise<Mutation<Order>> {
    this.validateKey(key)
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
      throw new ApiError('订单 UUID 无效', 400, 'INVALID_REQUEST')
    }
    const actor = this.requireWrite(session)
    this.validateReason(reason)
    if (target === 'CANCELLED' && quantity != null) {
      throw new ApiError('取消不接受 quantity；取消释放全部剩余预占', 400, 'INVALID_REQUEST')
    }
    const receipt = this.transitionReceipts.get(key)
    if (receipt) {
      const expectedEffect = quantity != null ? quantity : receipt.effectQuantity
      if (receipt.orderId !== id || receipt.target !== target || receipt.reason !== reason.trim()
          || receipt.effectQuantity !== expectedEffect) {
        throw new ApiError('幂等键已用于不同状态迁移', 409, 'IDEMPOTENCY_CONFLICT')
      }
      return { value: clone(this.requireOrder(receipt.orderId)), replayed: true }
    }
    const order = this.requireOrder(id)
    if (order.status !== 'RESERVED') throw new ApiError('订单已处于终态', 409, 'ORDER_STATE_CONFLICT')
    const effect = target === 'CANCELLED' ? order.quantity : (quantity ?? order.quantity)
    if (target === 'CONFIRMED' && (effect < 1 || effect > order.quantity)) {
      throw new ApiError('确认数量须在 1 与剩余预占之间', 400, 'INVALID_REQUEST')
    }
    const item = this.inventory.find((candidate) => candidate.sku === order.sku)
    if (!item || item.reserved < effect) throw new ApiError('预占台账不足', 409, 'STOCK_ADJUSTMENT_REJECTED')
    const at = this.now()
    item.reserved -= effect
    if (target === 'CANCELLED') item.available += effect
    item.version += 1
    item.updatedAt = at
    if (target === 'CANCELLED' || effect === order.quantity) {
      order.status = target
    } else {
      order.quantity -= effect
    }
    order.updatedAt = at
    order.version += 1
    this.transitionReceipts.set(key, { orderId: id, target, reason: reason.trim(), effectQuantity: effect })
    this.appendAudit('INVENTORY', item.sku, target === 'CANCELLED' ? 'STOCK_RELEASED' : 'STOCK_CONFIRMED', actor, key,
      { orderId: id, quantity: String(effect), available: String(item.available), reserved: String(item.reserved) }, at)
    const orderAction = target === 'CANCELLED' ? 'ORDER_CANCELLED'
      : (order.status === 'CONFIRMED' ? 'ORDER_CONFIRMED' : 'ORDER_PARTIAL_CONFIRMED')
    this.appendAudit('ORDER', id, orderAction, actor, key,
      { from: 'RESERVED', to: order.status, reason: reason.trim(), confirmedQuantity: String(effect) }, at)
    return { value: clone(order), replayed: false }
  }

  async rescheduleOrder(session: Session, id: string, expiresAt: string, reason: string, key: string): Promise<Mutation<Order>> {
    this.validateKey(key)
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
      throw new ApiError('订单 UUID 无效', 400, 'INVALID_REQUEST')
    }
    const actor = this.requireWrite(session)
    this.validateReason(reason)
    const nextMs = Date.parse(expiresAt)
    if (Number.isNaN(nextMs)) throw new ApiError('expiresAt 无效', 400, 'INVALID_REQUEST')
    const receipt = this.rescheduleReceipts.get(key)
    if (receipt) {
      if (receipt.orderId !== id || receipt.expiresAt !== expiresAt || receipt.reason !== reason.trim()) {
        throw new ApiError('幂等键已用于不同改期请求', 409, 'IDEMPOTENCY_CONFLICT')
      }
      return { value: clone(this.requireOrder(receipt.orderId)), replayed: true }
    }
    const order = this.requireOrder(id)
    if (order.status !== 'RESERVED') throw new ApiError('订单已处于终态', 409, 'ORDER_STATE_CONFLICT')
    const at = this.now()
    const nowMs = Date.parse(at)
    const currentExpiry = Date.parse(order.expiresAt ?? '')
    if (!Number.isNaN(currentExpiry) && currentExpiry <= nowMs) {
      throw new ApiError('已过期预留不可改期，须先过期释放或取消', 400, 'INVALID_REQUEST')
    }
    if (nextMs <= nowMs) throw new ApiError('新过期时间必须晚于当前时刻', 400, 'INVALID_REQUEST')
    if (nextMs > nowMs + DEMO_RESERVATION_TTL_MS) {
      throw new ApiError('新过期时间不可超过预留 TTL 窗口', 400, 'INVALID_REQUEST')
    }
    const previous = order.expiresAt ?? ''
    order.expiresAt = expiresAt
    order.updatedAt = at
    order.version += 1
    this.rescheduleReceipts.set(key, { orderId: id, expiresAt, reason: reason.trim() })
    this.appendAudit('ORDER', id, 'ORDER_RESCHEDULED', actor, key,
      { previousExpiresAt: previous, expiresAt, reason: reason.trim() }, at)
    return { value: clone(order), replayed: false }
  }

  async auditEvents(session: Session, type: AggregateType, id: string): Promise<AuditEvent[]> {
    this.requireAudit(session)
    return clone(this.audits.filter((event) => event.aggregateType === type && event.aggregateId === id)
      .sort((a, b) => a.sequence - b.sequence))
  }

  async verifyAudit(session: Session, type: AggregateType, id: string): Promise<AuditVerification> {
    const events = await this.auditEvents(session, type, id)
    let previous = '0'.repeat(64)
    for (const [index, event] of events.entries()) {
      if (event.sequence !== index + 1 || event.previousHash !== previous || event.eventHash !== this.demoHash(event)) {
        return { valid: false, checkedEvents: index + 1, message: `第 ${index + 1} 个事件校验失败` }
      }
      previous = event.eventHash
    }
    return { valid: true, checkedEvents: events.length, message: events.length ? '演示链完整；生产环境由 SHA-256 校验' : '该聚合暂无审计事件' }
  }

  private appendAudit(type: AggregateType, aggregateId: string, action: string, actor: Actor, requestId: string,
    payload: Record<string, string>, occurredAt: string): void {
    const chain = this.audits.filter((event) => event.aggregateType === type && event.aggregateId === aggregateId)
    const previousHash = chain.at(-1)?.eventHash ?? '0'.repeat(64)
    const event: AuditEvent = {
      id: this.nextId(), aggregateType: type, aggregateId, sequence: chain.length + 1,
      action, actorId: actor.id, actorName: actor.username, requestId, occurredAt,
      payloadJson: JSON.stringify(Object.fromEntries(Object.entries(payload).sort(([a], [b]) => a.localeCompare(b)))),
      previousHash, eventHash: '',
    }
    event.eventHash = this.demoHash(event)
    this.audits.push(event)
  }

  private demoHash(event: AuditEvent): string {
    const source = `${event.aggregateType}|${event.aggregateId}|${event.sequence}|${event.action}|${event.payloadJson}|${event.previousHash}`
    let hash = 2166136261
    for (let index = 0; index < source.length; index += 1) hash = Math.imul(hash ^ source.charCodeAt(index), 16777619)
    return `${(hash >>> 0).toString(16).padStart(8, '0')}${event.sequence.toString(16).padStart(8, '0')}`.padEnd(64, '0').slice(0, 64)
  }

  private requireActor(session: Session): Actor {
    let active = this.sessions.get(session.accessToken)
    if (!active && !this.revokedTokens.has(session.accessToken)) {
      const match = /^demo-(admin|operator|viewer|auditor)-not-a-production-credential$/.exec(session.accessToken)
      const actor = match ? demoActor(match[1]) : null
      if (actor) {
        active = { ...session, actor }
        this.sessions.set(session.accessToken, active)
      }
    }
    if (!active) throw new ApiError('演示会话无效，请重新登录', 401, 'UNAUTHENTICATED')
    return active.actor
  }

  private requireWrite(session: Session): Actor {
    const actor = this.requireActor(session)
    if (!actor.roles.some((role) => role === 'ADMIN' || role === 'OPERATOR')) throw new ApiError('当前角色没有写权限', 403, 'ACCESS_DENIED')
    return actor
  }

  private requireAudit(session: Session): Actor {
    const actor = this.requireActor(session)
    if (!actor.roles.some((role) => role === 'ADMIN' || role === 'AUDITOR')) throw new ApiError('当前角色没有审计权限', 403, 'ACCESS_DENIED')
    return actor
  }

  private requireOrder(id: string): Order {
    const order = this.orders.find((candidate) => candidate.id === id)
    if (!order) throw new ApiError('订单不存在', 404, 'NOT_FOUND')
    return order
  }

  private validateKey(key: string): void {
    if (!key || key.length > 128 || key !== key.trim() || /[\s\u0000-\u001f\u007f-\u009f]/.test(key)) {
      throw new ApiError('Idempotency-Key 必须是 1–128 位且无空白/控制字符', 400, 'INVALID_REQUEST')
    }
  }

  private validateSku(sku: string): void {
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(sku)) throw new ApiError('SKU 格式无效', 400, 'INVALID_REQUEST')
  }

  private validateReason(reason: string): void {
    if (!reason?.trim() || reason.length > 200 || reason !== reason.trim()) {
      throw new ApiError('请填写 1–200 字符且无首尾空格的审计原因', 400, 'INVALID_REQUEST')
    }
  }

  private systemActor(): Actor {
    return { id: '00000000-0000-0000-0000-000000000000', username: 'system', roles: ['ADMIN'] }
  }

  private nextId(): string {
    this.tick += 1
    return `00000000-0000-0000-0000-${this.tick.toString().padStart(12, '0')}`
  }

  private now(): string {
    return new Date(Date.UTC(2026, 0, 1, 9, this.tick)).toISOString()
  }
}

export const demoGateway = new DemoGateway()

export type Role = 'ADMIN' | 'OPERATOR' | 'VIEWER' | 'AUDITOR'
export type OrderStatus = 'RESERVED' | 'CONFIRMED' | 'CANCELLED'
export type AggregateType = 'ORDER' | 'INVENTORY' | 'SECURITY'

export interface Actor {
  id: string
  username: string
  roles: Role[]
}

export interface Session {
  /** Empty when authMode is cookie (HttpOnly cookie holds the secret). */
  accessToken: string
  tokenType: 'Bearer' | 'Cookie'
  expiresAt: string
  actor: Actor
  authMode?: 'bearer' | 'cookie'
}

export interface Inventory {
  sku: string
  available: number
  reserved: number
  version: number
  updatedAt: string
}

export interface InventoryOperation {
  requestId: string
  sku: string
  operation: 'CREATE' | 'ADJUST'
  delta: number
  reason: string
  resultingAvailable: number
  actorId: string
  createdAt: string
  replayed: boolean
}

export interface Order {
  id: string
  requestId: string
  sku: string
  quantity: number
  status: OrderStatus
  createdAt: string
  updatedAt: string
  /** ISO time; set for RESERVED (and retained after terminal transition). */
  expiresAt: string | null
  version: number
}

export interface OrderPage {
  items: Order[]
  nextCursor: string | null
}

export interface DashboardStats {
  skuCount: number
  availableStock: number
  reservedStock: number
  totalOrders: number
  reservedOrders: number
  confirmedOrders: number
  cancelledOrders: number
}

export interface AuditEvent {
  id: string
  aggregateType: AggregateType
  aggregateId: string
  sequence: number
  action: string
  actorId: string
  actorName: string
  requestId: string
  occurredAt: string
  payloadJson: string
  previousHash: string
  eventHash: string
}

export interface AuditVerification {
  valid: boolean
  checkedEvents: number
  message: string
}

export interface OrderQuery {
  status?: OrderStatus
  sku?: string
  cursor?: string
  limit?: number
}

export interface Mutation<T> {
  value: T
  replayed: boolean
}

export interface TradeGateway {
  login(username: string, password: string): Promise<Session>
  /** Current actor for the presented credential; used to revalidate a cookie session after reload. */
  me(session: Session): Promise<Actor>
  logout(session: Session): Promise<void>
  dashboard(session: Session): Promise<DashboardStats>
  listInventory(session: Session): Promise<Inventory[]>
  createInventory(session: Session, input: { sku: string; initialStock: number; reason: string }, key: string): Promise<Mutation<InventoryOperation>>
  adjustInventory(session: Session, sku: string, input: { delta: number; reason: string }, key: string): Promise<Mutation<InventoryOperation>>
  listOrders(session: Session, query?: OrderQuery): Promise<OrderPage>
  placeOrder(session: Session, input: { sku: string; quantity: number }, key: string): Promise<Mutation<Order>>
  transitionOrder(session: Session, id: string, target: 'CONFIRMED' | 'CANCELLED', reason: string, key: string, quantity?: number): Promise<Mutation<Order>>
  rescheduleOrder(session: Session, id: string, expiresAt: string, reason: string, key: string): Promise<Mutation<Order>>
  auditEvents(session: Session, type: AggregateType, id: string): Promise<AuditEvent[]>
  verifyAudit(session: Session, type: AggregateType, id: string): Promise<AuditVerification>
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

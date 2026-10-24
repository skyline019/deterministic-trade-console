import {
  ApiError,
  type AuditEvent,
  type AuditVerification,
  type DashboardStats,
  type Inventory,
  type InventoryOperation,
  type Mutation,
  type Order,
  type OrderPage,
  type OrderQuery,
  type Session,
  type TradeGateway,
} from './types'

/** bearer = Authorization header; cookie = HttpOnly __Host-TradeSession + double-submit CSRF. */
export type AuthMode = 'bearer' | 'cookie'

export function resolveAuthMode(): AuthMode {
  const forced = import.meta.env.VITE_AUTH_MODE
  if (forced === 'cookie' || forced === 'bearer') return forced
  if (typeof window !== 'undefined' && window.location.protocol === 'https:') return 'cookie'
  return 'bearer'
}

function readBrowserCookie(name: string): string | null {
  if (typeof document === 'undefined') return null
  const encoded = name.replace(/[$()*+.?[\\\]^{|}]/g, '\\$&')
  const match = document.cookie.match(new RegExp(`(?:^|; )${encoded}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE'
  body?: unknown
  idempotencyKey?: string
  authenticated?: boolean
}

async function request<T>(session: Session | null, path: string, options: RequestOptions = {}): Promise<Mutation<T>> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 10_000)
  const mode = session?.authMode ?? resolveAuthMode()
  const headers = new Headers({ Accept: 'application/json' })
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey)

  const useCookie = mode === 'cookie'
  if (session && options.authenticated !== false && !useCookie && session.accessToken) {
    headers.set('Authorization', `Bearer ${session.accessToken}`)
  }
  if (useCookie && options.authenticated !== false) {
    const method = options.method ?? 'GET'
    if (method !== 'GET') {
      const xsrf = readBrowserCookie('TradeXsrf')
      if (xsrf) headers.set('X-XSRF-TOKEN', xsrf)
    }
  }

  try {
    const response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
      cache: 'no-store',
      credentials: useCookie ? 'include' : 'omit',
      referrerPolicy: 'no-referrer',
    })
    const text = response.status === 204 ? '' : await response.text()
    let payload: { message?: string; code?: string } | undefined
    if (text) {
      try {
        payload = JSON.parse(text) as { message?: string; code?: string }
      } catch {
        if (response.ok) throw new ApiError('服务返回了无效 JSON', response.status, 'INVALID_RESPONSE')
      }
    }
    if (!response.ok) {
      if (response.status === 401 && options.authenticated !== false) {
        window.dispatchEvent(new CustomEvent('trade:session-expired'))
      }
      throw new ApiError(payload?.message ?? `HTTP ${response.status}`, response.status, payload?.code ?? 'HTTP_ERROR')
    }
    if (response.status !== 204 && payload === undefined) {
      throw new ApiError('服务返回了空响应', response.status, 'INVALID_RESPONSE')
    }
    return {
      value: payload as T,
      replayed: response.headers.get('Idempotent-Replay') === 'true',
    }
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError(options.idempotencyKey ? '请求超时，请使用相同幂等键重试' : '请求超时，请稍后重试', 0, 'TIMEOUT')
    }
    throw new ApiError('无法连接交易服务', 0, 'NETWORK_ERROR')
  } finally {
    window.clearTimeout(timeout)
  }
}

function orderQuery(query: OrderQuery = {}): string {
  const params = new URLSearchParams()
  if (query.status) params.set('status', query.status)
  if (query.sku) params.set('sku', query.sku)
  if (query.cursor) params.set('cursor', query.cursor)
  params.set('limit', String(query.limit ?? 30))
  return `?${params.toString()}`
}

export const httpGateway: TradeGateway = {
  async login(username, password) {
    const mode = resolveAuthMode()
    const result = await request<Session>(null, '/api/v1/auth/token', {
      method: 'POST',
      body: { username, password, delivery: mode },
      authenticated: false,
    })
    const value = result.value
    return {
      ...value,
      accessToken: value.accessToken ?? '',
      tokenType: mode === 'cookie' ? 'Cookie' : 'Bearer',
      authMode: mode,
    }
  },

  async logout(session) {
    await request<void>(session, '/api/v1/auth/token', { method: 'DELETE' })
  },

  async dashboard(session) {
    return (await request<DashboardStats>(session, '/api/v1/dashboard')).value
  },

  async listInventory(session) {
    return (await request<Inventory[]>(session, '/api/v1/inventory')).value
  },

  async createInventory(session, input, key) {
    return request<InventoryOperation>(session, '/api/v1/inventory', {
      method: 'POST', body: input, idempotencyKey: key,
    })
  },

  async adjustInventory(session, sku, input, key) {
    return request<InventoryOperation>(session, `/api/v1/inventory/${encodeURIComponent(sku)}/adjustments`, {
      method: 'POST', body: input, idempotencyKey: key,
    })
  },

  async listOrders(session, query) {
    const page = (await request<OrderPage>(session, `/api/v1/orders${orderQuery(query)}`)).value
    return { ...page, nextCursor: page.nextCursor ?? null }
  },

  async placeOrder(session, input, key) {
    return request<Order>(session, '/api/v1/orders', { method: 'POST', body: input, idempotencyKey: key })
  },

  async transitionOrder(session, id, target, reason, key, quantity) {
    const action = target === 'CONFIRMED' ? 'confirmation' : 'cancellation'
    const body: { reason: string; quantity?: number } = { reason }
    if (target === 'CONFIRMED' && quantity != null) body.quantity = quantity
    return request<Order>(session, `/api/v1/orders/${encodeURIComponent(id)}/${action}`, {
      method: 'POST', body, idempotencyKey: key,
    })
  },

  async rescheduleOrder(session, id, expiresAt, reason, key) {
    return request<Order>(session, `/api/v1/orders/${encodeURIComponent(id)}/reschedule`, {
      method: 'POST', body: { expiresAt, reason }, idempotencyKey: key,
    })
  },

  async auditEvents(session, type, id) {
    const params = new URLSearchParams({ type, id, limit: '200' })
    return (await request<AuditEvent[]>(session, `/api/v1/audit/events?${params}`)).value
  },

  async verifyAudit(session, type, id) {
    const params = new URLSearchParams({ type, id })
    return (await request<AuditVerification>(session, `/api/v1/audit/verification?${params}`)).value
  },
}

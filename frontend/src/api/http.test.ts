import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { httpGateway } from './http'
import type { Session } from './types'

const session: Session = {
  authMode: 'bearer',
  accessToken: 'a'.repeat(43),
  tokenType: 'Bearer',
  expiresAt: '2099-01-01T00:00:00Z',
  actor: { id: 'actor-id', username: 'admin', roles: ['ADMIN'] },
}

const order = {
  id: '00000000-0000-0000-0000-000000000001', requestId: 'request-1', sku: 'SKU-1', quantity: 2,
  status: 'RESERVED', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: 0,
}

describe('HTTP gateway contract', () => {
  const dispatchEvent = vi.fn()
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('window', {
      setTimeout: globalThis.setTimeout.bind(globalThis),
      clearTimeout: globalThis.clearTimeout.bind(globalThis),
      dispatchEvent,
      location: { protocol: 'http:' },
    })
    vi.stubGlobal('document', { cookie: '' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
    dispatchEvent.mockReset()
  })

  it('logs in without ambient credentials or an authorization header', async () => {
    fetchMock.mockResolvedValue(json(session))
    const result = await httpGateway.login('admin', 'secret')
    expect(result).toEqual(session)
    const [path, init] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/v1/auth/token')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('omit')
    expect(init.referrerPolicy).toBe('no-referrer')
    expect((init.headers as Headers).has('Authorization')).toBe(false)
    expect(init.body).toBe(JSON.stringify({ username: 'admin', password: 'secret', delivery: 'bearer' }))
    expect(init.credentials).toBe('omit')
  })

  it('sends bearer and idempotency headers and preserves replay metadata', async () => {
    fetchMock.mockResolvedValue(json(order, 200, { 'Idempotent-Replay': 'true' }))
    const result = await httpGateway.placeOrder(session, { sku: 'SKU-1', quantity: 2 }, 'request-1')
    expect(result).toEqual({ value: order, replayed: true })
    const [, init] = fetchMock.mock.calls[0]
    const headers = init.headers as Headers
    expect(headers.get('Authorization')).toBe(`Bearer ${session.accessToken}`)
    expect(headers.get('Idempotency-Key')).toBe('request-1')
    expect(init.cache).toBe('no-store')
  })

  it('encodes order filters and normalizes an omitted next cursor', async () => {
    fetchMock.mockResolvedValue(json({ items: [] }))
    const page = await httpGateway.listOrders(session, { status: 'RESERVED', sku: 'SKU-1', cursor: 'a+b/', limit: 50 })
    expect(page).toEqual({ items: [], nextCursor: null })
    const [path] = fetchMock.mock.calls[0]
    const url = new URL(path, 'http://console.local')
    expect(url.searchParams.get('status')).toBe('RESERVED')
    expect(url.searchParams.get('sku')).toBe('SKU-1')
    expect(url.searchParams.get('cursor')).toBe('a+b/')
    expect(url.searchParams.get('limit')).toBe('50')
  })

  it('maps a 401 problem and emits exactly one session-expired event', async () => {
    fetchMock.mockResolvedValue(json({ code: 'UNAUTHENTICATED', message: 'valid bearer token required' }, 401))
    await expect(httpGateway.dashboard(session)).rejects.toMatchObject({
      status: 401, code: 'UNAUTHENTICATED', message: 'valid bearer token required',
    })
    expect(dispatchEvent).toHaveBeenCalledTimes(1)
    expect(dispatchEvent.mock.calls[0][0].type).toBe('trade:session-expired')
  })

  it('does not clear a session for validation or permission failures', async () => {
    fetchMock.mockResolvedValue(json({ code: 'ACCESS_DENIED', message: 'missing permission' }, 403))
    await expect(httpGateway.listInventory(session)).rejects.toMatchObject({ status: 403, code: 'ACCESS_DENIED' })
    expect(dispatchEvent).not.toHaveBeenCalled()
  })

  it('distinguishes malformed success JSON from a network failure', async () => {
    fetchMock.mockResolvedValue(new Response('{bad-json', { status: 200 }))
    await expect(httpGateway.dashboard(session)).rejects.toMatchObject({ status: 200, code: 'INVALID_RESPONSE' })
  })

  it('keeps the HTTP status when an error response is not JSON', async () => {
    fetchMock.mockResolvedValue(new Response('gateway unavailable', { status: 503 }))
    await expect(httpGateway.dashboard(session)).rejects.toMatchObject({ status: 503, code: 'HTTP_ERROR' })
  })

  it('rejects an unexpected empty success response', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 200 }))
    await expect(httpGateway.dashboard(session)).rejects.toMatchObject({ status: 200, code: 'INVALID_RESPONSE' })
  })

  it('accepts a bodiless logout response', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await expect(httpGateway.logout(session)).resolves.toBeUndefined()
    expect(fetchMock.mock.calls[0][1].method).toBe('DELETE')
  })

  it('maps network and timeout failures without hiding retry semantics', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('connection refused'))
    await expect(httpGateway.dashboard(session)).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
    fetchMock.mockRejectedValueOnce(new DOMException('aborted', 'AbortError'))
    await expect(httpGateway.placeOrder(session, { sku: 'SKU-1', quantity: 1 }, 'retry-key'))
      .rejects.toMatchObject({ status: 0, code: 'TIMEOUT', message: '请求超时，请使用相同幂等键重试' })
  })
})

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

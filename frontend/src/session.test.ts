import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./api', () => ({
  gateway: {
    login: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
  },
}))

const COOKIE_SESSION = {
  accessToken: '',
  tokenType: 'Cookie' as const,
  authMode: 'cookie' as const,
  expiresAt: new Date(Date.now() + 600_000).toISOString(),
  actor: { id: '1', username: 'admin', roles: ['ADMIN' as const] },
}

function installMemoryStorage(): Storage {
  const map = new Map<string, string>()
  const storage: Storage = {
    get length() {
      return map.size
    },
    clear: () => map.clear(),
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    key: (index) => [...map.keys()][index] ?? null,
    removeItem: (key) => {
      map.delete(key)
    },
    setItem: (key, value) => {
      map.set(key, String(value))
    },
  }
  vi.stubGlobal('sessionStorage', storage)
  return storage
}

describe('session persistence modes', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.unstubAllEnvs()
    installMemoryStorage()
  })

  it('defaults to sessionStorage restore across module reload', async () => {
    const stored = {
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      actor: { id: '1', username: 'admin', roles: ['ADMIN'] },
    }
    sessionStorage.setItem('deterministic-trade.session.v2', JSON.stringify(stored))
    const { useSession, sessionPersistMode } = await import('./session')
    expect(sessionPersistMode).toBe('session')
    expect(useSession().session.value?.accessToken).toBe('tok')
  })

  it('memory mode never writes sessionStorage and starts empty', async () => {
    vi.stubEnv('VITE_SESSION_PERSIST', 'memory')
    const { useSession, sessionPersistMode } = await import('./session')
    const { gateway } = await import('./api')
    expect(sessionPersistMode).toBe('memory')
    expect(useSession().session.value).toBeNull()

    vi.mocked(gateway.login).mockResolvedValue({
      accessToken: 'mem-tok',
      tokenType: 'Bearer',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      actor: { id: '1', username: 'admin', roles: ['ADMIN'] },
    })
    await useSession().login('admin', 'x')
    expect(useSession().session.value?.accessToken).toBe('mem-tok')
    expect(sessionStorage.getItem('deterministic-trade.session.v2')).toBeNull()
  })

  it('restores a cookie session even though it carries no access token', async () => {
    sessionStorage.setItem('deterministic-trade.session.v2', JSON.stringify(COOKIE_SESSION))
    const { useSession } = await import('./session')
    expect(useSession().session.value?.authMode).toBe('cookie')
    expect(useSession().session.value?.accessToken).toBe('')
  })

  it('still rejects a bearer session with no token or an expired deadline', async () => {
    sessionStorage.setItem('deterministic-trade.session.v2', JSON.stringify({
      ...COOKIE_SESSION, authMode: 'bearer', tokenType: 'Bearer',
    }))
    const { useSession } = await import('./session')
    expect(useSession().session.value).toBeNull()
    expect(sessionStorage.getItem('deterministic-trade.session.v2')).toBeNull()
  })

  it('revalidates a cookie session against /auth/me and adopts the server actor', async () => {
    sessionStorage.setItem('deterministic-trade.session.v2', JSON.stringify(COOKIE_SESSION))
    const { useSession, revalidateSession } = await import('./session')
    const { gateway } = await import('./api')
    vi.mocked(gateway.me).mockResolvedValue({ id: '1', username: 'admin', roles: ['AUDITOR'] })

    await revalidateSession()

    expect(gateway.me).toHaveBeenCalledTimes(1)
    expect(useSession().session.value?.actor.roles).toEqual(['AUDITOR'])
  })

  it('drops the session when the cookie is no longer accepted', async () => {
    sessionStorage.setItem('deterministic-trade.session.v2', JSON.stringify(COOKIE_SESSION))
    const { useSession, revalidateSession } = await import('./session')
    const { gateway } = await import('./api')
    const { ApiError } = await import('./api/types')
    vi.mocked(gateway.me).mockRejectedValue(new ApiError('unauthorized', 401, 'UNAUTHENTICATED'))

    await revalidateSession()

    expect(useSession().session.value).toBeNull()
    expect(sessionStorage.getItem('deterministic-trade.session.v2')).toBeNull()
  })

  it('keeps the session when revalidation fails for transport reasons', async () => {
    sessionStorage.setItem('deterministic-trade.session.v2', JSON.stringify(COOKIE_SESSION))
    const { useSession, revalidateSession } = await import('./session')
    const { gateway } = await import('./api')
    const { ApiError } = await import('./api/types')
    vi.mocked(gateway.me).mockRejectedValue(new ApiError('offline', 0, 'NETWORK_ERROR'))

    await revalidateSession()

    expect(useSession().session.value?.authMode).toBe('cookie')
  })
})

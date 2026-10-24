import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./api', () => ({
  gateway: {
    login: vi.fn(),
    logout: vi.fn(),
  },
}))

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
})

import { computed, ref } from 'vue'
import { gateway } from './api'
import { ApiError, type Actor, type Session } from './api/types'

const STORAGE_KEY = 'deterministic-trade.session.v2'

/**
 * session — persist in sessionStorage (XSS can still read while the page is open).
 * memory — process-only; survives neither reload nor tab duplicate (narrows persistence / forensics surface).
 * Set VITE_SESSION_PERSIST=memory for stricter browser threat model; default keeps same-tab restore UX.
 */
type PersistMode = 'session' | 'memory'
const PERSIST_MODE: PersistMode =
  import.meta.env.VITE_SESSION_PERSIST === 'memory' ? 'memory' : 'session'

/** Idle auto-expire (ms). 0 disables. XSS still wins while the page is active. */
const IDLE_MS = Number(import.meta.env.VITE_SESSION_IDLE_MS ?? 0)

function isCookieSession(value: Session): boolean {
  return value.authMode === 'cookie' || value.tokenType === 'Cookie'
}

/**
 * A stored session is only a hint. Bearer sessions are self-contained (the token proves identity);
 * cookie sessions deliberately carry no token at all, so presence of an access token must not be the
 * restore condition — otherwise every reload of the HTTPS console logs the operator out while a
 * still-valid HttpOnly cookie is sitting in the browser.
 */
function isRestorable(value: Session): boolean {
  if (!value.actor || Number.isNaN(Date.parse(value.expiresAt))) return false
  if (Date.parse(value.expiresAt) <= Date.now()) return false
  return isCookieSession(value) || Boolean(value.accessToken)
}

function restore(): Session | null {
  if (PERSIST_MODE === 'memory') {
    return null
  }
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const session = JSON.parse(raw) as Session
    if (!isRestorable(session)) {
      sessionStorage.removeItem(STORAGE_KEY)
      return null
    }
    return session
  } catch {
    sessionStorage.removeItem(STORAGE_KEY)
    return null
  }
}

const session = ref<Session | null>(restore())
const authenticating = ref(false)
let idleTimer: ReturnType<typeof setTimeout> | undefined

function clearIdleTimer(): void {
  if (idleTimer !== undefined) {
    clearTimeout(idleTimer)
    idleTimer = undefined
  }
}

function armIdleTimer(): void {
  clearIdleTimer()
  if (IDLE_MS > 0 && session.value) {
    idleTimer = setTimeout(() => {
      persist(null)
    }, IDLE_MS)
  }
}

function persist(value: Session | null): void {
  session.value = value
  if (PERSIST_MODE === 'session') {
    if (value) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value))
    else sessionStorage.removeItem(STORAGE_KEY)
  } else if (!value) {
    sessionStorage.removeItem(STORAGE_KEY)
  }
  if (value) armIdleTimer()
  else clearIdleTimer()
}

/**
 * Revalidate a restored cookie session against the server before trusting it.
 * The stored record only says "this tab logged in once"; {@code GET /auth/me} is what proves the
 * HttpOnly cookie is still valid, still unrevoked, and still belongs to an enabled user.
 * A 401/403 clears the session; a transport error keeps it so a flaky network does not log the
 * operator out (subsequent calls fail visibly instead).
 */
export async function revalidateSession(): Promise<void> {
  const current = session.value
  if (!current || !isCookieSession(current)) return
  try {
    const actor = await gateway.me(current)
    if (session.value !== current) return
    persist({ ...current, actor })
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      persist(null)
    }
  }
}

/** Call from UI event handlers to postpone idle expiry without touching the token. */
export function touchSessionActivity(): void {
  if (session.value) armIdleTimer()
}

export function useSession() {
  const actor = computed(() => session.value?.actor ?? null)
  const canWrite = computed(() => hasRole(actor.value, ['ADMIN', 'OPERATOR']))
  const canAudit = computed(() => hasRole(actor.value, ['ADMIN', 'AUDITOR']))

  async function login(username: string, password: string): Promise<void> {
    authenticating.value = true
    try {
      persist(await gateway.login(username, password))
    } finally {
      authenticating.value = false
    }
  }

  async function logout(): Promise<void> {
    const active = session.value
    persist(null)
    if (active) await gateway.logout(active)
  }

  function expire(): void {
    persist(null)
  }

  return {
    session, actor, canWrite, canAudit, authenticating, login, logout, expire, revalidateSession,
  }
}

export function hasRole(actor: Actor | null, roles: Actor['roles']): boolean {
  return Boolean(actor?.roles.some((role) => roles.includes(role)))
}

export const sessionPersistMode: PersistMode = PERSIST_MODE

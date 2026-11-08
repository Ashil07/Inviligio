import { setAccessTokenProvider, setSessionRefresher } from '@/lib/api/client'
import { refreshSession, type AuthResponse } from './api'

/**
 * Framework-free holder of the current session. The access token lives only here (memory), so a
 * page reload loses it and the session is restored from the HttpOnly refresh cookie instead.
 * React reads it through AuthProvider; the Axios client reads it through the registered providers.
 */
type Listener = (auth: AuthResponse | null) => void

let current: AuthResponse | null = null
let refreshInFlight: Promise<AuthResponse | null> | null = null
const listeners = new Set<Listener>()

export const sessionStore = {
  get(): AuthResponse | null {
    return current
  },

  set(auth: AuthResponse | null) {
    current = auth
    listeners.forEach((l) => l(auth))
  },

  /** Replaces the session details (e.g. college status after onboarding) and keeps the token. */
  updateSession(session: AuthResponse['session']) {
    if (current) sessionStore.set({ ...current, session })
  },

  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  /**
   * Single-flight refresh: concurrent callers (bootstrap, several 401s at once) share one request,
   * because the refresh token rotates on every use.
   */
  refresh(): Promise<AuthResponse | null> {
    refreshInFlight ??= refreshSession()
      .then((auth) => {
        sessionStore.set(auth)
        return auth
      })
      .catch(() => {
        sessionStore.set(null)
        return null
      })
      .finally(() => {
        refreshInFlight = null
      })
    return refreshInFlight
  },
}

setAccessTokenProvider(() => current?.accessToken ?? null)
setSessionRefresher(async () => (await sessionStore.refresh())?.accessToken ?? null)

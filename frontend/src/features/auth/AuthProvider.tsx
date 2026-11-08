import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { logout, type AuthResponse } from './api'
import { AuthContext, type AuthState } from './useAuth'
import { sessionStore } from './sessionStore'

function toState(auth: AuthResponse | null): AuthState {
  return auth ? { status: 'authenticated', session: auth.session } : { status: 'anonymous' }
}

/**
 * Restores the session from the refresh cookie on start-up, then mirrors sessionStore into React.
 * `restoreOnMount={false}` is for tests that seed the store directly.
 */
export function AuthProvider({ children, restoreOnMount = true }: { children: ReactNode; restoreOnMount?: boolean }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<AuthState>(() =>
    restoreOnMount ? { status: 'loading' } : toState(sessionStore.get()),
  )

  useEffect(() => {
    const unsubscribe = sessionStore.subscribe((auth) => setState(toState(auth)))
    if (restoreOnMount) void sessionStore.refresh()
    return unsubscribe
  }, [restoreOnMount])

  const signIn = useCallback((auth: AuthResponse) => sessionStore.set(auth), [])

  const signOut = useCallback(async () => {
    try {
      await logout()
    } finally {
      sessionStore.set(null)
      queryClient.clear()
    }
  }, [queryClient])

  const value = useMemo(() => ({ state, signIn, signOut }), [state, signIn, signOut])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

import { createContext, useContext } from 'react'
import type { AuthResponse, SessionInfo } from './api'

export type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'authenticated'; session: SessionInfo }

export interface AuthContextValue {
  state: AuthState
  signIn: (auth: AuthResponse) => void
  signOut: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}

/** The signed-in session. Only call below <RequireAuth>. */
export function useSession(): SessionInfo {
  const { state } = useAuth()
  if (state.status !== 'authenticated') throw new Error('useSession used outside an authenticated route')
  return state.session
}

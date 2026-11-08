import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import type { AuthResponse, Role, SessionInfo } from '@/features/auth/api'
import { AuthProvider } from '@/features/auth/AuthProvider'
import { sessionStore } from '@/features/auth/sessionStore'

export function makeSession(roles: Role[] = ['EXAM_CELL_ADMIN'], overrides: Partial<SessionInfo> = {}): SessionInfo {
  return {
    user: { id: 'u1', email: 'admin@example.test', fullName: 'Asha Admin' },
    college: { id: 'c1', code: 'GEC', name: 'Government Engineering College', status: 'ONBOARDING' },
    membershipId: 'm1',
    facultyId: null,
    roles,
    ...overrides,
  }
}

export function makeAuth(session: SessionInfo = makeSession()): AuthResponse {
  return { accessToken: 'token-1', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z', session }
}

/** Renders a route tree in memory with a seeded (or empty) session and no network bootstrap. */
export function renderRoutes(routes: RouteObject[], { path = '/', auth = null }: { path?: string; auth?: AuthResponse | null } = {}) {
  sessionStore.set(auth)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider restoreOnMount={false}>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  )
  return { ...utils, router }
}

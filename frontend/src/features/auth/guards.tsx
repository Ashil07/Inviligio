import { Navigate, Outlet, useLocation } from 'react-router'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { ROLE_HOME, ROLE_PRIORITY, type Role } from './api'
import { useAuth, useSession } from './useAuth'

/** Route guards are a UX convenience only. The API enforces every permission. */

export function RequireAuth() {
  const { state } = useAuth()
  const location = useLocation()
  if (state.status === 'loading') return <FullPageSpinner label="Restoring your session" />
  if (state.status === 'anonymous') return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <Outlet />
}

export function PublicOnly() {
  const { state } = useAuth()
  if (state.status === 'loading') return <FullPageSpinner label="Loading" />
  if (state.status === 'authenticated') return <Navigate to="/" replace />
  return <Outlet />
}

export function RequireRole({ role }: { role: Role }) {
  const session = useSession()
  if (!session.roles.includes(role)) {
    return (
      <div className="mx-auto max-w-xl px-4 py-16 text-center">
        <h1 className="text-xl font-semibold text-slate-900">No access</h1>
        <p className="mt-2 text-slate-600">Your account does not have access to this area.</p>
      </div>
    )
  }
  return <Outlet />
}

/** Sends a signed-in user to the area of their highest-priority role. */
export function HomeRedirect() {
  const session = useSession()
  const role = ROLE_PRIORITY.find((r) => session.roles.includes(r))
  if (!role) {
    return (
      <div className="mx-auto max-w-xl px-4 py-16 text-center">
        <h1 className="text-xl font-semibold text-slate-900">No role assigned</h1>
        <p className="mt-2 text-slate-600">Ask your examination cell to assign you a role.</p>
      </div>
    )
  }
  return <Navigate to={ROLE_HOME[role]} replace />
}

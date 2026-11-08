import { LogOut } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { ROLE_HOME, ROLE_LABEL, ROLE_PRIORITY } from '@/features/auth/api'
import { useAuth, useSession } from '@/features/auth/useAuth'

/** Shell for signed-in pages: college, role areas the user can switch between, and sign-out. */
export function AppLayout() {
  const session = useSession()
  const { signOut } = useAuth()
  const navigate = useNavigate()
  const [signingOut, setSigningOut] = useState(false)
  const roles = ROLE_PRIORITY.filter((r) => session.roles.includes(r))

  async function handleSignOut() {
    setSigningOut(true)
    try {
      await signOut()
    } finally {
      navigate('/login', { replace: true })
    }
  }

  return (
    <div className="min-h-dvh">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <div className="min-w-0">
            <p className="text-xs font-semibold tracking-wide text-indigo-700">INVIGILO</p>
            <p className="truncate text-sm font-medium text-slate-900">
              {session.college.name} <span className="text-slate-500">({session.college.code})</span>
              {session.college.status === 'ONBOARDING' && (
                <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                  Setup in progress
                </span>
              )}
            </p>
          </div>

          {roles.length > 1 && (
            <nav aria-label="Areas" className="flex gap-1">
              {roles.map((role) => (
                <NavLink
                  key={role}
                  to={ROLE_HOME[role]}
                  className={({ isActive }) =>
                    `rounded-md px-3 py-1.5 text-sm ${isActive ? 'bg-indigo-50 font-medium text-indigo-700' : 'text-slate-600 hover:bg-slate-100'}`
                  }
                >
                  {ROLE_LABEL[role]}
                </NavLink>
              ))}
            </nav>
          )}

          <div className="ml-auto flex items-center gap-3">
            <span className="hidden text-sm text-slate-600 sm:inline">{session.user.fullName}</span>
            <button
              type="button"
              onClick={() => void handleSignOut()}
              disabled={signingOut}
              className="inline-flex items-center gap-1.5 rounded-md px-2 py-1.5 text-sm text-slate-600 hover:bg-slate-100 disabled:opacity-50"
            >
              <LogOut className="size-4" aria-hidden />
              Sign out
            </button>
          </div>
        </div>
      </header>
      <Outlet />
    </div>
  )
}

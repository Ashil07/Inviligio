import { LogOut, Menu, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { Wordmark } from '@/components/Brand'
import { ROLE_HOME, ROLE_PRIORITY, type Role } from '@/features/auth/api'
import { useAuth, useSession } from '@/features/auth/useAuth'
import { AREA_TITLE, areaOf, isActive, navFor, type Area } from './navigation'

const ROLE_SHORT: Record<Role, string> = { EXAM_CELL_ADMIN: 'Exam cell', HOD: 'HOD', FACULTY: 'Faculty' }
const ROLE_FULL: Record<Role, string> = { EXAM_CELL_ADMIN: 'Exam cell', HOD: 'Head of department', FACULTY: 'Faculty' }

/**
 * Shell for signed-in pages. Wide screens: a fixed sidebar with the college, the role areas the user can
 * switch between, the area's grouped navigation and sign-out. Narrow screens: a top bar whose menu button
 * opens the same sidebar as a drawer.
 */
export function AppLayout() {
  const { pathname } = useLocation()
  const area = areaOf(pathname)
  // The drawer belongs to the page it was opened on, so it closes by itself after navigating.
  const [menuPath, setMenuPath] = useState<string | null>(null)
  const menuOpen = menuPath === pathname
  const menuButton = useRef<HTMLButtonElement>(null)
  const session = useSession()

  const closeMenu = useCallback(() => {
    setMenuPath(null)
    menuButton.current?.focus()
  }, [setMenuPath])

  const current = area
    ? navFor(area, session.college.status === 'ONBOARDING')
        .flatMap((g) => g.items)
        .find((i) => isActive(i, pathname))
    : undefined

  return (
    <div className="min-h-dvh bg-white">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:bg-ink focus:px-3 focus:py-2 focus:text-sm focus:text-white">
        Skip to content
      </a>

      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 border-r border-neutral-200 bg-white lg:flex">
        <Sidebar area={area} />
      </aside>

      <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-neutral-200 bg-white/95 px-4 backdrop-blur pt-[env(safe-area-inset-top)] lg:hidden">
        <button
          ref={menuButton}
          type="button"
          onClick={() => setMenuPath(pathname)}
          className="-ml-2 inline-flex size-10 items-center justify-center text-ink"
          aria-label="Open menu"
          aria-expanded={menuOpen}
        >
          <Menu className="size-5" aria-hidden />
        </button>
        <Wordmark />
        {current && area && (
          <p className="ml-auto truncate text-sm text-neutral-500">
            <span className="sr-only">{AREA_TITLE[area]}: </span>
            {current.label}
          </p>
        )}
      </header>

      {menuOpen && (
        <Drawer onClose={closeMenu}>
          <Sidebar area={area} />
        </Drawer>
      )}

      <div className="lg:pl-64">
        <main id="main" tabIndex={-1} className="mx-auto w-full max-w-[1200px] px-4 py-6 outline-none sm:px-6 sm:py-8 lg:px-10 lg:py-10">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function Drawer({ onClose, children }: { onClose: () => void; children: ReactNode }) {
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    panel.current?.querySelector<HTMLElement>('button[aria-label="Close menu"]')?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = previous
      document.removeEventListener('keydown', onKey)
    }
  }, [onClose])

  return (
    <div className="fixed inset-0 z-40 lg:hidden">
      <div className="absolute inset-0 bg-ink/40" onClick={onClose} aria-hidden />
      <div ref={panel} role="dialog" aria-modal="true" aria-label="Menu" className="absolute inset-y-0 left-0 flex w-[min(20rem,86vw)] bg-white">
        <button
          type="button"
          onClick={onClose}
          className="absolute right-2 top-2 z-10 inline-flex size-10 items-center justify-center text-ink"
          aria-label="Close menu"
        >
          <X className="size-5" aria-hidden />
        </button>
        {children}
      </div>
    </div>
  )
}

function Sidebar({ area }: { area: Area | null }) {
  const session = useSession()
  const { signOut } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [signingOut, setSigningOut] = useState(false)
  const roles = ROLE_PRIORITY.filter((r) => session.roles.includes(r))
  const groups = area ? navFor(area, session.college.status === 'ONBOARDING') : []

  async function handleSignOut() {
    setSigningOut(true)
    try {
      await signOut()
    } finally {
      navigate('/login', { replace: true })
    }
  }

  return (
    <div className="flex min-h-0 w-full flex-col pb-[env(safe-area-inset-bottom)]">
      <div className="px-5 pb-4 pt-5">
        <Wordmark />
        <div className="mt-5">
          <p className="eyebrow">College</p>
          <p className="mt-1 text-sm font-medium leading-snug text-ink">{session.college.name}</p>
          <p className="text-xs text-neutral-500">
            {session.college.code}
            {session.college.status === 'ONBOARDING' && <span className="ml-2 font-medium text-amber-700">· Setup in progress</span>}
          </p>
        </div>

        {roles.length > 1 && (
          <nav aria-label="Areas" className="mt-4">
            <div className="grid border border-neutral-200" style={{ gridTemplateColumns: `repeat(${roles.length}, minmax(0, 1fr))` }}>
              {roles.map((role, i) => {
                const active = areaOf(ROLE_HOME[role]) === area
                return (
                  <Link
                    key={role}
                    to={ROLE_HOME[role]}
                    title={ROLE_FULL[role]}
                    aria-current={active ? 'page' : undefined}
                    className={`truncate px-2 py-1.5 text-center text-xs font-medium ${i > 0 ? 'border-l border-neutral-200' : ''} ${
                      active ? 'bg-ink text-white' : 'text-neutral-600 hover:bg-neutral-50 hover:text-ink'
                    }`}
                  >
                    {ROLE_SHORT[role]}
                  </Link>
                )
              })}
            </div>
          </nav>
        )}
      </div>

      <nav aria-label={area ? AREA_TITLE[area] : 'Main'} className="min-h-0 flex-1 overflow-y-auto border-t border-neutral-200 px-3 py-3 [scrollbar-width:thin]">
        {groups.map((group, gi) => (
          <div key={group.label ?? gi} className={gi > 0 ? 'mt-4' : undefined}>
            {group.label && <p className="eyebrow mb-1 px-2">{group.label}</p>}
            <ul className="space-y-px">
              {group.items.map((item) => {
                const active = isActive(item, pathname)
                const Icon = item.icon
                return (
                  <li key={item.to}>
                    <Link
                      to={item.to}
                      aria-current={active ? 'page' : undefined}
                      className={`group relative flex h-8 items-center gap-3 px-2 text-sm transition-colors ${
                        active ? 'bg-neutral-100 font-medium text-ink' : 'text-neutral-600 hover:bg-neutral-50 hover:text-ink'
                      }`}
                    >
                      {active && <span className="absolute inset-y-1.5 left-0 w-0.5 bg-accent" aria-hidden />}
                      <Icon className={`size-4 shrink-0 ${active ? 'text-ink' : 'text-neutral-400 group-hover:text-ink'}`} strokeWidth={1.75} aria-hidden />
                      {item.label}
                    </Link>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </nav>

      <div className="flex items-center gap-3 border-t border-neutral-200 px-5 py-3">
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-ink">{session.user.fullName}</p>
          <p className="truncate text-xs text-neutral-500">{session.user.email}</p>
        </div>
        <button
          type="button"
          onClick={() => void handleSignOut()}
          disabled={signingOut}
          className="inline-flex items-center gap-1.5 px-2 py-1.5 text-sm text-neutral-600 hover:text-ink disabled:opacity-50"
        >
          <LogOut className="size-4" aria-hidden />
          Sign out
        </button>
      </div>
    </div>
  )
}

import { Link, Outlet, useLocation } from 'react-router'

const TABS = [
  { to: '/admin/settings/college', label: 'College details' },
  { to: '/admin/settings/policy', label: 'Examination policy' },
]

/** College settings: one navigation entry, with the college details and the examination policy as tabs. */
export function SettingsLayout() {
  const { pathname } = useLocation()
  return (
    <div>
      <p className="eyebrow">Settings</p>
      <nav aria-label="Settings" className="-mx-4 mb-8 mt-2 overflow-x-auto border-b border-neutral-200 px-4 sm:mx-0 sm:px-0">
        <ul className="flex gap-6">
          {TABS.map((t) => {
            const active = pathname === t.to || pathname.startsWith(t.to + '/')
            return (
              <li key={t.to}>
                <Link
                  to={t.to}
                  aria-current={active ? 'page' : undefined}
                  className={`-mb-px inline-block whitespace-nowrap border-b-2 py-2.5 text-sm ${
                    active ? 'border-ink font-medium text-ink' : 'border-transparent text-neutral-500 hover:text-ink'
                  }`}
                >
                  {t.label}
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>
      <Outlet />
    </div>
  )
}

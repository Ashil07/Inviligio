import { NavLink, Outlet } from 'react-router'

export interface AreaLink {
  to: string
  label: string
  end?: boolean
}

/** Section navigation for a role area (HOD, faculty). */
export function AreaLayout({ label, links }: { label: string; links: AreaLink[] }) {
  return (
    <div>
      <nav aria-label={label} className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl gap-1 overflow-x-auto px-4">
          {links.map((l) => (
            <NavLink
              key={l.to}
              to={l.to}
              end={l.end}
              className={({ isActive }) =>
                `whitespace-nowrap border-b-2 px-3 py-2.5 text-sm ${
                  isActive ? 'border-indigo-600 font-medium text-indigo-700' : 'border-transparent text-slate-600 hover:text-slate-900'
                }`
              }
            >
              {l.label}
            </NavLink>
          ))}
        </div>
      </nav>
      <Outlet />
    </div>
  )
}

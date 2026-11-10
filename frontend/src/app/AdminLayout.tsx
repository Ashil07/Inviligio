import { NavLink, Outlet } from 'react-router'
import { useSession } from '@/features/auth/useAuth'

/** Exam-cell area: section navigation plus page content. */
export function AdminLayout() {
  const session = useSession()
  const links =
    session.college.status === 'ONBOARDING'
      ? [
          { to: '/admin', label: 'Overview', end: true },
          { to: '/admin/setup', label: 'College setup', end: false },
        ]
      : [
          { to: '/admin', label: 'Overview', end: true },
          { to: '/admin/departments', label: 'Departments', end: false },
          { to: '/admin/subjects', label: 'Subjects', end: false },
          { to: '/admin/halls', label: 'Halls', end: false },
          { to: '/admin/faculty', label: 'Faculty', end: false },
          { to: '/admin/import', label: 'Import', end: false },
          { to: '/admin/exams', label: 'Examinations', end: false },
          { to: '/admin/availability', label: 'Availability', end: false },
          { to: '/admin/swaps', label: 'Swaps', end: false },
          { to: '/admin/analytics', label: 'Fairness', end: false },
          { to: '/admin/reports', label: 'Reports', end: false },
          { to: '/admin/settings/college', label: 'College', end: false },
          { to: '/admin/settings/policy', label: 'Policy', end: false },
        ]
  return (
    <div>
      <nav aria-label="Exam cell" className="border-b border-slate-200 bg-white">
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

/** Padding/width wrapper for admin pages that are not full-width layouts. */
export function AdminPage() {
  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <Outlet />
    </main>
  )
}

import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { useCollege } from '@/features/college/api'
import { HodDashboard } from '@/features/hod/HodDashboard'
import { FacultyDashboard } from '@/features/myduties/FacultyDashboard'
import { useSession } from '@/features/auth/useAuth'
import { usePolicy } from '@/features/policy/api'
import { formatDateTime } from '@/lib/forms'

/**
 * Landing page per role area. Each area fills in as its milestone lands
 * (see docs/DEVELOPMENT_PLAN.md) — no placeholder buttons that do nothing.
 */
function AreaHome({ title, children }: { title: string; children?: ReactNode }) {
  const session = useSession()
  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
      <p className="mt-1 text-slate-600">Welcome, {session.user.fullName}.</p>
      {children}
    </main>
  )
}

export function AdminHomePage() {
  const session = useSession()
  if (session.college.status === 'ONBOARDING') {
    return (
      <AreaHome title="Examination cell">
        <section className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-5 text-amber-900">
          <h2 className="font-medium">College setup is not finished</h2>
          <p className="mt-1 text-sm">
            Enter your institution details and choose the examination rules Invigilo should follow when allocating duties.
          </p>
          <Link
            to="/admin/setup"
            className="mt-4 inline-flex rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
          >
            Start college setup
          </Link>
        </section>
      </AreaHome>
    )
  }
  return (
    <AreaHome title="Examination cell">
      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        <CollegeSummary />
        <PolicySummary />
      </div>
    </AreaHome>
  )
}

function SummaryCard({ title, to, children }: { title: string; to: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h2 className="font-medium text-slate-900">{title}</h2>
        <Link to={to} className="text-sm text-indigo-700 hover:underline">
          Edit
        </Link>
      </div>
      <div className="mt-2 text-sm text-slate-600">{children}</div>
    </section>
  )
}

function CollegeSummary() {
  const { data } = useCollege()
  return (
    <SummaryCard title="College" to="/admin/settings/college">
      {data ? (
        <>
          <p>{data.profile.affiliation}</p>
          <p>Academic year {data.profile.academicYear}</p>
        </>
      ) : (
        <p>Loading...</p>
      )}
    </SummaryCard>
  )
}

function PolicySummary() {
  const { data } = usePolicy()
  return (
    <SummaryCard title="Examination policy" to="/admin/settings/policy">
      {data ? (
        <p>
          Version {data.version}, saved {formatDateTime(data.createdAt)} by {data.createdByName ?? 'unknown'}.
        </p>
      ) : (
        <p>Loading...</p>
      )}
    </SummaryCard>
  )
}

export function HodHomePage() {
  return (
    <AreaHome title="Department">
      <HodDashboard />
    </AreaHome>
  )
}

export function FacultyHomePage() {
  return (
    <AreaHome title="My duties">
      <FacultyDashboard />
    </AreaHome>
  )
}

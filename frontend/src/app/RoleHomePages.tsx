import { ArrowRight } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { useCollege } from '@/features/college/api'
import { HodDashboard } from '@/features/hod/HodDashboard'
import { FacultyDashboard } from '@/features/myduties/FacultyDashboard'
import { useSession } from '@/features/auth/useAuth'
import { usePolicy } from '@/features/policy/api'
import { formatDate, usePeriods, type PeriodStatus } from '@/features/schedule/api'
import { formatDateTime } from '@/lib/forms'

/**
 * Landing page per role area. Each area fills in as its milestone lands
 * (see docs/DEVELOPMENT_PLAN.md) — no placeholder buttons that do nothing.
 */
function AreaHome({ title, children }: { title: string; children?: ReactNode }) {
  const session = useSession()
  return (
    <div>
      <div className="border-b border-neutral-200 pb-5">
        <p className="eyebrow">Welcome, {session.user.fullName}</p>
        <h1 className="mt-2 text-2xl font-bold text-neutral-900 sm:text-3xl">{title}</h1>
      </div>
      {children}
    </div>
  )
}

export function AdminHomePage() {
  const session = useSession()
  if (session.college.status === 'ONBOARDING') {
    return (
      <AreaHome title="Examination cell">
        <section className="mt-8 max-w-2xl border-l-2 border-accent bg-neutral-50 p-5 sm:p-6">
          <h2 className="text-lg font-semibold text-neutral-900">College setup is not finished</h2>
          <p className="mt-1 text-sm text-neutral-600">
            Enter your institution details and choose the examination rules Invigilo should follow when allocating duties.
          </p>
          <Link
            to="/admin/setup"
            className="mt-5 inline-flex h-10 items-center gap-2 bg-ink px-4 text-sm font-medium text-white transition-colors hover:bg-accent"
          >
            Start college setup <ArrowRight className="size-4" aria-hidden />
          </Link>
        </section>
      </AreaHome>
    )
  }
  return (
    <AreaHome title="Examination cell">
      <div className="mt-8 grid grid-cols-1 gap-10 lg:grid-cols-12">
        <div className="lg:col-span-8">
          <RecentPeriods />
        </div>
        <div className="space-y-8 lg:col-span-4">
          <CollegeSummary />
          <PolicySummary />
        </div>
      </div>
      <QuickLinks />
    </AreaHome>
  )
}

const PERIOD_STATUS: Record<PeriodStatus, string> = {
  PLANNING: 'Planning',
  ALLOCATING: 'Allocating',
  PUBLISHED: 'Published',
  CLOSED: 'Closed',
}

function RecentPeriods() {
  const { data, isLoading } = usePeriods()
  const recent = [...(data ?? [])].sort((a, b) => b.startDate.localeCompare(a.startDate)).slice(0, 4)
  return (
    <section aria-labelledby="recent-periods">
      <div className="flex items-baseline justify-between gap-4 border-b border-ink pb-2">
        <h2 id="recent-periods" className="text-lg font-semibold text-neutral-900">Exam periods</h2>
        <Link to="/admin/exams" className="link text-sm">All periods</Link>
      </div>
      {isLoading ? (
        <p className="py-6 text-sm text-neutral-500">Loading...</p>
      ) : recent.length === 0 ? (
        <p className="py-6 text-sm text-neutral-600">
          No exam periods yet. <Link to="/admin/exams" className="link">Create the first one</Link>.
        </p>
      ) : (
        <ul className="divide-y divide-neutral-200">
          {recent.map((p) => (
            <li key={p.id}>
              <Link to={`/admin/exams/${p.id}`} className="group flex items-center gap-4 py-4">
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium text-ink group-hover:underline">{p.name}</p>
                  <p className="mt-0.5 text-sm text-neutral-500">
                    {formatDate(p.startDate)} - {formatDate(p.endDate)} · {p.sessionCount} sessions
                  </p>
                </div>
                <span className={`shrink-0 text-xs font-medium ${p.status === 'PUBLISHED' ? 'text-emerald-700' : 'text-neutral-500'}`}>
                  {PERIOD_STATUS[p.status]}
                </span>
                <ArrowRight className="size-4 shrink-0 text-neutral-300 group-hover:text-ink" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

const QUICK_LINKS = [
  { to: '/admin/faculty', title: 'Faculty', text: 'People, departments and duty restrictions' },
  { to: '/admin/availability', title: 'Availability', text: 'Leave and unavailability requests' },
  { to: '/admin/swaps', title: 'Swap requests', text: 'Swaps waiting for approval' },
  { to: '/admin/reports', title: 'Reports', text: 'Duty chart, workload and fairness files' },
]

function QuickLinks() {
  return (
    <section aria-label="Shortcuts" className="mt-12">
      <ul className="grid gap-px overflow-hidden border border-neutral-200 bg-neutral-200 sm:grid-cols-2 lg:grid-cols-4">
        {QUICK_LINKS.map((q, i) => (
          <li key={q.to} className="bg-white">
            <Link to={q.to} className="group block h-full p-5 transition-colors hover:bg-neutral-50">
              <p className="text-xs font-semibold text-accent">{String(i + 1).padStart(2, '0')}</p>
              <p className="mt-3 flex items-center justify-between font-semibold text-ink">
                {q.title}
                <ArrowRight className="size-4 text-neutral-300 transition-transform group-hover:translate-x-0.5 group-hover:text-ink" aria-hidden />
              </p>
              <p className="mt-1 text-sm text-neutral-500">{q.text}</p>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}

function SummaryCard({ title, to, children }: { title: string; to: string; children: ReactNode }) {
  return (
    <section>
      <div className="flex items-baseline justify-between border-b border-ink pb-2">
        <h2 className="text-lg font-semibold text-neutral-900">{title}</h2>
        <Link to={to} className="link text-sm">
          Edit
        </Link>
      </div>
      <div className="mt-3 space-y-0.5 text-sm text-neutral-600">{children}</div>
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

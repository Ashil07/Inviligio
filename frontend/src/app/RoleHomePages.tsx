import type { ReactNode } from 'react'
import { useSession } from '@/features/auth/useAuth'

/**
 * Landing page per role area. Deliberately informational: each area fills in as its milestone
 * lands (see docs/DEVELOPMENT_PLAN.md) — no placeholder buttons that do nothing.
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
  return (
    <AreaHome title="Examination cell">
      {session.college.status === 'ONBOARDING' && (
        <section className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          <h2 className="font-medium">College setup is not finished</h2>
          <p className="mt-1">
            Institution details and examination policies are configured in the onboarding step, which is the next
            part of Invigilo to be built.
          </p>
        </section>
      )}
    </AreaHome>
  )
}

export function HodHomePage() {
  return <AreaHome title="Department" />
}

export function FacultyHomePage() {
  return <AreaHome title="My duties" />
}

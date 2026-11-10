import { Info } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { ErrorAlert } from '@/components/ui/form'
import { Section, SelectField } from '@/components/ui/fields'
import { Table } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { describeWhen, useAvailabilityList } from '@/features/availability/api'
import { formatDate, hhmm } from '@/features/schedule/api'
import { useSwapList } from '@/features/swaps/api'
import { toApiProblem } from '@/lib/api/client'
import { formatDateTime } from '@/lib/forms'
import { useDepartmentOverview, type DepartmentDuty, type MemberRow } from './api'

const LOAD_STYLE = {
  OVER: { label: 'Over', className: 'bg-red-50 text-red-700' },
  UNDER: { label: 'Under', className: 'bg-amber-100 text-amber-800' },
  BALANCED: { label: 'Balanced', className: 'bg-emerald-50 text-emerald-700' },
} as const

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/** The HOD home: the department's duties and workload in a published chart, and leave waiting for a decision. */
export function HodDashboard() {
  const [periodId, setPeriodId] = useState<string>()
  const overview = useDepartmentOverview(periodId)

  if (overview.isPending) return <FullPageSpinner label="Loading your department" />
  if (overview.error) {
    const p = toApiProblem(overview.error)
    if (p.code === 'NO_DEPARTMENT_SCOPE') {
      return (
        <p className="mt-6 flex items-start gap-2 rounded-md border border-slate-200 bg-white p-4 text-sm text-slate-700">
          <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
          Your login is not linked to a faculty record, so Invigilo does not know your department. Ask the examination cell to link it.
        </p>
      )
    }
    return (
      <div className="mt-6">
        <ErrorAlert>{p.detail ?? 'Could not load your department.'}</ErrorAlert>
      </div>
    )
  }

  const data = overview.data
  const over = data.faculty.filter((f) => f.load === 'OVER').length
  const under = data.faculty.filter((f) => f.load === 'UNDER').length
  return (
    <div className="mt-6 space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-lg font-semibold text-slate-900">
            {data.department.name} ({data.department.code})
          </p>
          {data.periodId ? (
            <p className="text-sm text-slate-600">
              {data.periodName}: duty chart version {data.chartVersion}, published {formatDateTime(data.publishedAt)}.
            </p>
          ) : (
            <p className="text-sm text-slate-600">No duty chart has been published yet. Duties and workload appear here once the examination cell publishes one.</p>
          )}
        </div>
        {data.periods.length > 1 && (
          <div className="w-64">
            <SelectField
              label="Exam period"
              value={data.periodId ?? ''}
              onChange={(e) => setPeriodId(e.target.value)}
              options={data.periods.map((p) => ({ value: p.id, label: `${p.name} (v${p.publishedVersion})` }))}
            />
          </div>
        )}
      </div>

      {data.periodId && (
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-5">
          <Stat label="Duties in department" value={data.departmentDuties} />
          <Stat label="Per person (department)" value={data.departmentAverage} />
          <Stat label="Per person (college)" value={data.collegeAverage} />
          <Stat label="Overloaded" value={over} tone={over > 0 ? 'text-red-700' : undefined} />
          <Stat label="Underloaded" value={under} tone={under > 0 ? 'text-amber-700' : undefined} />
        </dl>
      )}

      <PendingLeave />
      <PendingSwaps />

      <Section
        title="Workload"
        description="Each person against an even share of the college's duties for their duty weight. Over or under means more than one duty away from it."
      >
        <WorkloadTable rows={data.faculty} showDuties={!!data.periodId} />
      </Section>

      {data.periodId && (
        <Section title="Department duties" description="Who from your department is on duty in each session.">
          <DutyTable duties={data.duties} />
        </Section>
      )}
    </div>
  )
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className={`text-lg font-semibold ${tone ?? 'text-slate-900'}`}>{value}</dd>
    </div>
  )
}

function WorkloadTable({ rows, showDuties }: { rows: MemberRow[]; showDuties: boolean }) {
  return (
    <Table head={showDuties ? ['Faculty', 'Designation', 'Duties', 'As chief', 'Even share', 'Workload'] : ['Faculty', 'Designation']} empty={rows.length === 0 && 'No faculty in this department.'}>
      {rows.map((f) => (
        <tr key={f.facultyId}>
          <td className="px-4 py-2 text-slate-800">
            {f.name} <span className="text-slate-500">({f.code})</span>
          </td>
          <td className="px-4 py-2 text-slate-600">{f.designation ?? '-'}</td>
          {showDuties && (
            <>
              <td className="px-4 py-2 font-medium text-slate-900">{f.duties}</td>
              <td className="px-4 py-2 text-slate-600">{f.chiefDuties}</td>
              <td className="px-4 py-2 text-slate-600">{f.active ? f.fairShare : '-'}</td>
              <td className="px-4 py-2">
                {f.load ? (
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${LOAD_STYLE[f.load].className}`}>{LOAD_STYLE[f.load].label}</span>
                ) : (
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">Inactive</span>
                )}
              </td>
            </>
          )}
        </tr>
      ))}
    </Table>
  )
}

function DutyTable({ duties }: { duties: DepartmentDuty[] }) {
  return (
    <Table head={['Date', 'Session', 'Hall', 'Faculty', 'Role']} empty={duties.length === 0 && 'Nobody from your department has a duty in this chart.'}>
      {duties.map((d) => (
        <tr key={d.assignmentId}>
          <td className="px-4 py-2 text-slate-800">{formatDate(d.date)}</td>
          <td className="px-4 py-2 text-slate-600">
            {d.sessionLabel} {hhmm(d.startTime)}-{hhmm(d.endTime)}
          </td>
          <td className="px-4 py-2 text-slate-800">
            {d.hallCode} <span className="text-slate-500">· {d.hallName}</span>
          </td>
          <td className="px-4 py-2 text-slate-800">{d.facultyName}</td>
          <td className="px-4 py-2 text-slate-600">{d.role === 'CHIEF' ? 'Chief invigilator' : 'Invigilator'}</td>
        </tr>
      ))}
    </Table>
  )
}

function PendingLeave() {
  const pending = useAvailabilityList('PENDING')
  const list = pending.data ?? []
  return (
    <Section title="Leave waiting for a decision">
      {pending.isPending ? (
        <p className="text-sm text-slate-600">Loading requests...</p>
      ) : pending.error ? (
        <ErrorAlert>{toApiProblem(pending.error).detail ?? 'Could not load leave requests.'}</ErrorAlert>
      ) : (
        <div className="space-y-2 text-sm text-slate-700">
          <p>{list.length === 0 ? 'No requests are waiting.' : `${plural(list.length, 'request')} waiting.`}</p>
          {list.length > 0 && (
            <ul className="divide-y divide-slate-100 rounded-md border border-slate-200 bg-white">
              {list.slice(0, 5).map((r) => (
                <li key={r.id} className="px-3 py-2">
                  <span className="font-medium text-slate-900">{r.faculty.name}</span> · {describeWhen(r)}
                  {r.reason && <span className="text-slate-500"> · {r.reason}</span>}
                </li>
              ))}
            </ul>
          )}
          <Link to="/hod/requests" className="inline-block font-medium text-indigo-700 hover:underline">
            Review leave requests
          </Link>
        </div>
      )}
    </Section>
  )
}

function PendingSwaps() {
  const swaps = useSwapList('PENDING_HOD')
  const count = swaps.data?.length ?? 0
  if (!swaps.data || count === 0) return null
  return (
    <p className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
      {count === 1 ? '1 swap is' : `${count} swaps are`} waiting for your approval.{' '}
      <Link to="/hod/swaps" className="font-medium text-indigo-700 hover:underline">
        Review swap requests
      </Link>
    </p>
  )
}

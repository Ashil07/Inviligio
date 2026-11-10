import { CalendarDays, ChevronLeft, ChevronRight, Download, Info } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Button, ErrorAlert } from '@/components/ui/form'
import { Section } from '@/components/ui/fields'
import { Table } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useMyAvailability } from '@/features/availability/api'
import { useMySwaps } from '@/features/swaps/api'
import { formatDate, hhmm } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import { useDownloadCalendar, useMyDuties, type MyDuty } from './api'

const roleName = (d: MyDuty) => (d.role === 'CHIEF' ? 'Chief invigilator' : 'Invigilator')
const place = (d: MyDuty) => `${d.hallCode} · ${d.hallName}${d.building ? `, ${d.building}` : ''}`

/** The faculty home: next duty, calendar, upcoming and past duties, leave requests, calendar download. */
export function FacultyDashboard() {
  const duties = useMyDuties()
  const download = useDownloadCalendar()

  if (duties.isPending) return <FullPageSpinner label="Loading your duties" />
  if (duties.error) {
    const p = toApiProblem(duties.error)
    if (p.code === 'NOT_A_FACULTY_MEMBER') {
      return (
        <p className="mt-6 flex items-start gap-2 rounded-md border border-slate-200 bg-white p-4 text-sm text-slate-700">
          <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
          Your login is not linked to a faculty record, so there are no duties to show. Ask the examination cell if this is wrong.
        </p>
      )
    }
    return (
      <div className="mt-6">
        <ErrorAlert>{p.detail ?? 'Could not load your duties.'}</ErrorAlert>
      </div>
    )
  }

  const { upcoming, past, chiefDuties, today } = duties.data
  const next = upcoming[0]
  return (
    <div className="mt-6 space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <dl className="grid flex-1 grid-cols-3 gap-3 sm:max-w-md">
          <Stat label="Upcoming" value={upcoming.length} />
          <Stat label="Done" value={past.length} />
          <Stat label="As chief" value={chiefDuties} />
        </dl>
        <div className="flex flex-col items-end gap-1">
          <Button variant="secondary" loading={download.isPending} disabled={upcoming.length + past.length === 0} onClick={() => download.mutate()}>
            <Download className="mr-2 size-4" aria-hidden /> Download calendar
          </Button>
          <span className="text-xs text-slate-500">An .ics file for Google Calendar, Outlook or your phone.</span>
        </div>
      </div>
      {download.error && <ErrorAlert>{toApiProblem(download.error).detail ?? 'Could not download your calendar.'}</ErrorAlert>}

      {next ? (
        <section aria-label="Next duty" className="rounded-lg border border-indigo-200 bg-indigo-50 p-4">
          <p className="text-xs font-medium uppercase tracking-wide text-indigo-700">Next duty</p>
          <p className="mt-1 text-lg font-semibold text-slate-900">
            {formatDate(next.date)} · {next.sessionLabel} {hhmm(next.startTime)}-{hhmm(next.endTime)}
          </p>
          <p className="text-sm text-slate-700">
            {roleName(next)} in {place(next)}
          </p>
          <p className="text-sm text-slate-600">{next.colleagues.length > 0 ? `With ${next.colleagues.join(', ')}` : 'No other invigilator in this hall.'}</p>
          <p className="mt-1 text-xs text-slate-500">{next.periodName}</p>
        </section>
      ) : (
        <p className="rounded-md border border-slate-200 bg-white p-4 text-sm text-slate-600">
          {past.length > 0 ? 'You have no upcoming duties.' : 'No duties yet. They appear here once the examination cell publishes a duty chart.'}
        </p>
      )}

      <MonthCalendar duties={[...upcoming, ...past]} today={today} start={next?.date ?? today} />

      <Section title="Upcoming duties">
        <DutyTable duties={upcoming} empty="Nothing coming up." swappable />
      </Section>
      <Section title="Past duties">
        <DutyTable duties={past} empty="No past duties yet." />
      </Section>
      <SwapRequests />
      <LeaveRequests />
    </div>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="text-lg font-semibold text-slate-900">{value}</dd>
    </div>
  )
}

function DutyTable({ duties, empty, swappable = false }: { duties: MyDuty[]; empty: string; swappable?: boolean }) {
  return (
    <Table head={['Date', 'Session', 'Hall', 'Role', 'With', ...(swappable ? [''] : [])]} empty={duties.length === 0 && empty}>
      {duties.map((d) => (
        <tr key={d.assignmentId}>
          <td className="px-4 py-2 text-slate-800">{formatDate(d.date)}</td>
          <td className="px-4 py-2 text-slate-600">
            {d.sessionLabel} {hhmm(d.startTime)}-{hhmm(d.endTime)}
          </td>
          <td className="px-4 py-2 text-slate-800">{place(d)}</td>
          <td className="px-4 py-2 text-slate-600">{roleName(d)}</td>
          <td className="px-4 py-2 text-slate-600">{d.colleagues.join(', ') || '-'}</td>
          {swappable && (
            <td className="px-4 py-2 text-right">
              {d.status === 'ASSIGNED' && (
                <Link to={`/faculty/swaps?duty=${d.assignmentId}`} className="text-sm font-medium text-indigo-700 hover:underline" aria-label={`Swap the duty on ${formatDate(d.date)} ${d.sessionLabel}`}>
                  Swap
                </Link>
              )}
            </td>
          )}
        </tr>
      ))}
    </Table>
  )
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

/** "2027-03-01" for a local date, without time-zone shifts. */
const iso = (y: number, m: number, d: number) => `${y}-${String(m + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`

/** A month grid (weeks start on Monday) with each duty shown on its day. */
function MonthCalendar({ duties, today, start }: { duties: MyDuty[]; today: string; start: string }) {
  const [year0, month0] = start.split('-').map(Number) as [number, number]
  const [month, setMonth] = useState({ year: year0, month: month0 - 1 })
  const byDay = new Map<string, MyDuty[]>()
  for (const d of duties) byDay.set(d.date, [...(byDay.get(d.date) ?? []), d])

  const first = new Date(month.year, month.month, 1)
  const daysInMonth = new Date(month.year, month.month + 1, 0).getDate()
  const leading = (first.getDay() + 6) % 7
  const cells: (number | null)[] = [...Array<null>(leading).fill(null), ...Array.from({ length: daysInMonth }, (_, i) => i + 1)]
  while (cells.length % 7 !== 0) cells.push(null)
  const title = first.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
  const move = (by: number) => {
    const d = new Date(month.year, month.month + by, 1)
    setMonth({ year: d.getFullYear(), month: d.getMonth() })
  }

  return (
    <Section title="Calendar">
      <div className="flex items-center justify-between">
        <button type="button" className="rounded p-1 text-slate-600 hover:bg-slate-100" aria-label="Previous month" onClick={() => move(-1)}>
          <ChevronLeft className="size-5" aria-hidden />
        </button>
        <h3 className="flex items-center gap-2 text-sm font-semibold text-slate-900">
          <CalendarDays className="size-4" aria-hidden /> {title}
        </h3>
        <button type="button" className="rounded p-1 text-slate-600 hover:bg-slate-100" aria-label="Next month" onClick={() => move(1)}>
          <ChevronRight className="size-5" aria-hidden />
        </button>
      </div>
      <div role="grid" aria-label={`Duties in ${title}`} className="mt-3 grid grid-cols-7 gap-1 text-xs">
        {WEEKDAYS.map((w) => (
          <div key={w} role="columnheader" className="py-1 text-center font-medium text-slate-500">
            {w}
          </div>
        ))}
        {cells.map((day, i) => {
          if (day === null) return <div key={`blank-${i}`} />
          const date = iso(month.year, month.month, day)
          const mine = byDay.get(date) ?? []
          return (
            <div
              key={date}
              role="gridcell"
              aria-label={mine.length ? `${date}: ${mine.map((d) => `${d.sessionLabel} ${d.hallCode}`).join(', ')}` : date}
              className={`min-h-14 rounded border p-1 ${mine.length ? 'border-indigo-300 bg-indigo-50' : 'border-slate-100 bg-white'} ${date === today ? 'ring-2 ring-indigo-400' : ''}`}
            >
              <div className={`text-right ${date === today ? 'font-semibold text-indigo-700' : 'text-slate-500'}`}>{day}</div>
              {mine.map((d) => (
                <div key={d.assignmentId} className="truncate font-medium text-indigo-800">
                  {d.sessionLabel} {d.hallCode}
                </div>
              ))}
            </div>
          )
        })}
      </div>
    </Section>
  )
}

function LeaveRequests() {
  const requests = useMyAvailability()
  const pending = requests.data?.filter((r) => r.status === 'PENDING').length ?? 0
  return (
    <Section title="Leave and availability">
      <p className="text-sm text-slate-600">
        {requests.isPending
          ? 'Loading your requests...'
          : pending > 0
            ? `${pending} request${pending === 1 ? ' is' : 's are'} waiting for a decision.`
            : 'No requests waiting for a decision.'}{' '}
        <Link to="/faculty/availability" className="font-medium text-indigo-700 hover:underline">
          Manage leave requests
        </Link>
      </p>
    </Section>
  )
}

function SwapRequests() {
  const swaps = useMySwaps()
  const waiting = swaps.data?.received.filter((s) => s.status === 'PENDING_RECIPIENT').length ?? 0
  if (!swaps.data || waiting === 0) return null
  return (
    <p className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
      {waiting === 1 ? 'A colleague has' : `${waiting} colleagues have`} asked you to swap a duty.{' '}
      <Link to="/faculty/swaps" className="font-medium text-indigo-700 hover:underline">
        Answer swap requests
      </Link>
    </p>
  )
}

import { AlertTriangle } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { CheckboxField, Section, SuccessNotice } from '@/components/ui/fields'
import { EditPanel, PageHeader, Table } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { toApiProblem } from '@/lib/api/client'
import { formatDate, hhmm, useCreateSession, useDeletePeriod, useGenerateSessions, usePeriod, useSessions, type Period } from './api'
import { PeriodForm } from './PeriodsPage'

export function PeriodDetailPage() {
  const periodId = useParams().periodId!
  const period = usePeriod(periodId)
  const sessions = useSessions(periodId)
  const remove = useDeletePeriod()
  const navigate = useNavigate()
  const [panel, setPanel] = useState<'edit' | 'session' | 'generate' | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  if (period.error) return <ErrorAlert>{toApiProblem(period.error).detail ?? 'Could not load the period.'}</ErrorAlert>
  if (!period.data) return <FullPageSpinner label="Loading examination period" />
  const p = period.data

  async function handleDelete() {
    if (!window.confirm(`Delete ${p.name} with all its sessions, exams and seating? This cannot be undone.`)) return
    try {
      await remove.mutateAsync(p.id)
      navigate('/admin/exams', { replace: true })
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not delete.')
    }
  }

  const totals = (sessions.data ?? []).reduce((acc, s) => ({ students: acc.students + s.students, positions: acc.positions + s.positions }), {
    students: 0,
    positions: 0,
  })

  return (
    <div className="space-y-6">
      <Link to="/admin/exams" className="text-sm text-indigo-700 hover:underline">← All examination periods</Link>
      <PageHeader
        title={p.name}
        description={`${formatDate(p.startDate)} to ${formatDate(p.endDate)} · ${p.sessionCount} sessions · ${totals.students} students seated · ${totals.positions} invigilator positions`}
        actions={
          <>
            <Button variant="secondary" onClick={() => setPanel('edit')}>Edit</Button>
            <Button variant="secondary" onClick={() => void handleDelete()}>Delete</Button>
          </>
        }
      />
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {panel === 'edit' && <PeriodForm period={p} onDone={() => setPanel(null)} />}
      <div className="flex flex-wrap gap-2">
        <Link
          to={`/admin/exams/${p.id}/allocation`}
          className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
        >
          Allocate duties
        </Link>
        <Button variant="secondary" onClick={() => setPanel('session')}>Add session</Button>
        <Button variant="secondary" onClick={() => setPanel('generate')}>Generate sessions</Button>
      </div>
      {panel === 'session' && <AddSession period={p} onDone={() => setPanel(null)} />}
      {panel === 'generate' && <GenerateSessions period={p} onDone={() => setPanel(null)} />}

      {sessions.error && <ErrorAlert>{toApiProblem(sessions.error).detail ?? 'Could not load sessions.'}</ErrorAlert>}
      <Table head={['Date', 'Session', 'Time', 'Exams', 'Halls', 'Students', 'Positions', '']} empty={sessions.data?.length === 0 && 'No sessions yet.'}>
        {sessions.data?.map((s) => (
          <tr key={s.id}>
            <td className="px-4 py-2 text-slate-700">{formatDate(s.date)}</td>
            <td className="px-4 py-2">
              <Link to={`/admin/exams/sessions/${s.id}`} className="font-medium text-indigo-700 hover:underline">{s.label}</Link>
            </td>
            <td className="px-4 py-2 text-slate-600">{hhmm(s.startTime)}-{hhmm(s.endTime)}</td>
            <td className="px-4 py-2 text-slate-600">{s.examCount}</td>
            <td className="px-4 py-2 text-slate-600">{s.hallCount}</td>
            <td className="px-4 py-2 text-slate-600">{s.students}</td>
            <td className="px-4 py-2 text-slate-600">{s.positions}</td>
            <td className="px-4 py-2 text-amber-700">
              {s.warningCount > 0 && (
                <span className="inline-flex items-center gap-1" title="Open the session to see the warnings">
                  <AlertTriangle className="size-4" aria-hidden /> {s.warningCount} warning{s.warningCount > 1 ? 's' : ''}
                </span>
              )}
            </td>
          </tr>
        ))}
      </Table>
    </div>
  )
}

function AddSession({ period, onDone }: { period: Period; onDone: () => void }) {
  const create = useCreateSession(period.id)
  const [values, setValues] = useState({ date: period.startDate, label: 'FN', startTime: '09:30', endTime: '12:30' })
  const [problem, setProblem] = useState<string | null>(null)
  const set = (k: keyof typeof values) => (e: React.ChangeEvent<HTMLInputElement>) => setValues({ ...values, [k]: e.target.value })
  async function submit(ev: React.FormEvent) {
    ev.preventDefault()
    setProblem(null)
    try {
      await create.mutateAsync(values)
      onDone()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not add the session.')
    }
  }
  return (
    <EditPanel title="New session" onClose={onDone}>
      <form onSubmit={(ev) => void submit(ev)} className="space-y-4">
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        <div className="grid gap-4 sm:grid-cols-4">
          <TextField label="Date" type="date" min={period.startDate} max={period.endDate} value={values.date} onChange={set('date')} required />
          <TextField label="Label" hint="e.g. FN, AN" value={values.label} onChange={set('label')} required />
          <TextField label="Starts" type="time" value={values.startTime} onChange={set('startTime')} required />
          <TextField label="Ends" type="time" value={values.endTime} onChange={set('endTime')} required />
        </div>
        <Button type="submit" loading={create.isPending}>Add session</Button>
      </form>
    </EditPanel>
  )
}

function GenerateSessions({ period, onDone }: { period: Period; onDone: () => void }) {
  const generate = useGenerateSessions(period.id)
  const [from, setFrom] = useState(period.startDate)
  const [to, setTo] = useState(period.endDate)
  const [skipSunday, setSkipSunday] = useState(true)
  const [skipSaturday, setSkipSaturday] = useState(false)
  const [slots, setSlots] = useState([
    { label: 'FN', startTime: '09:30', endTime: '12:30' },
    { label: 'AN', startTime: '13:30', endTime: '16:30' },
  ])
  const [result, setResult] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  async function submit(ev: React.FormEvent) {
    ev.preventDefault()
    setProblem(null)
    try {
      const skipDays = [...(skipSunday ? ['SUNDAY'] : []), ...(skipSaturday ? ['SATURDAY'] : [])]
      const r = await generate.mutateAsync({ fromDate: from, toDate: to, skipDays, slots: slots.filter((s) => s.label.trim()) })
      setResult(`Created ${r.created} sessions${r.skipped ? `; ${r.skipped} already existed` : ''}.`)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not generate sessions.')
    }
  }

  return (
    <EditPanel title="Generate sessions" onClose={onDone}>
      <form onSubmit={(ev) => void submit(ev)} className="space-y-4">
        <p className="text-sm text-slate-600">Creates the same daily sessions for every date in the range. Existing sessions are kept.</p>
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        {result && <SuccessNotice>{result}</SuccessNotice>}
        <div className="grid gap-4 sm:grid-cols-4">
          <TextField label="From" type="date" min={period.startDate} max={period.endDate} value={from} onChange={(e) => setFrom(e.target.value)} />
          <TextField label="To" type="date" min={period.startDate} max={period.endDate} value={to} onChange={(e) => setTo(e.target.value)} />
          <div className="flex flex-col justify-end gap-2 pb-2 sm:col-span-2">
            <CheckboxField label="Skip Sundays" checked={skipSunday} onChange={(e) => setSkipSunday(e.target.checked)} />
            <CheckboxField label="Skip Saturdays" checked={skipSaturday} onChange={(e) => setSkipSaturday(e.target.checked)} />
          </div>
        </div>
        <Section title="Daily sessions">
          {slots.map((slot, i) => (
            <div key={i} className="grid gap-4 sm:grid-cols-4">
              <TextField label={`Session ${i + 1} label`} value={slot.label} onChange={(e) => setSlots(slots.map((s, j) => (j === i ? { ...s, label: e.target.value } : s)))} />
              <TextField label="Starts" type="time" value={slot.startTime} onChange={(e) => setSlots(slots.map((s, j) => (j === i ? { ...s, startTime: e.target.value } : s)))} />
              <TextField label="Ends" type="time" value={slot.endTime} onChange={(e) => setSlots(slots.map((s, j) => (j === i ? { ...s, endTime: e.target.value } : s)))} />
              <div className="flex items-end">
                <Button type="button" variant="secondary" disabled={slots.length === 1} onClick={() => setSlots(slots.filter((_, j) => j !== i))}>Remove</Button>
              </div>
            </div>
          ))}
          {slots.length < 4 && (
            <Button type="button" variant="secondary" onClick={() => setSlots([...slots, { label: '', startTime: '09:00', endTime: '12:00' }])}>
              Add a daily session
            </Button>
          )}
        </Section>
        <Button type="submit" loading={generate.isPending}>Generate</Button>
      </form>
    </EditPanel>
  )
}

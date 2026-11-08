import { AlertTriangle } from 'lucide-react'
import { useDeferredValue, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { CheckboxField, Section, SelectField } from '@/components/ui/fields'
import { EditPanel, Table } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useHalls } from '@/features/halls/api'
import { useSubjects } from '@/features/subjects/api'
import { toApiProblem } from '@/lib/api/client'
import { formatDate, hhmm, useSession, useSessionMutations, type ExamType, type SessionDetail, type SessionHallView } from './api'

type Mutations = ReturnType<typeof useSessionMutations>

/** One session: its exams, the halls used, how students are seated and the invigilators each hall needs. */
export function SessionPage() {
  const sessionId = useParams().sessionId!
  const session = useSession(sessionId)
  const mutations = useSessionMutations(sessionId)
  const navigate = useNavigate()
  const [problem, setProblem] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)

  if (session.error) return <ErrorAlert>{toApiProblem(session.error).detail ?? 'Could not load the session.'}</ErrorAlert>
  if (!session.data) return <FullPageSpinner label="Loading session" />
  const s = session.data

  async function run<T>(action: () => Promise<T>, fallback: string) {
    setProblem(null)
    try {
      return await action()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? fallback)
      return undefined
    }
  }

  async function handleDelete() {
    if (!window.confirm(`Delete the ${s.label} session on ${formatDate(s.date)} with its exams and seating?`)) return
    const ok = await run(() => mutations.remove.mutateAsync(), 'Could not delete the session.')
    if (ok !== undefined) navigate(`/admin/exams/${s.periodId}`, { replace: true })
  }

  return (
    <div className="space-y-6">
      <Link to={`/admin/exams/${s.periodId}`} className="text-sm text-indigo-700 hover:underline">← {s.periodName}</Link>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">
            {formatDate(s.date)} · {s.label}
          </h1>
          <p className="mt-1 text-sm text-slate-600">
            {hhmm(s.startTime)}-{hhmm(s.endTime)} · {s.totalStudents} students seated · {s.totalPositions} invigilator positions
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setEditing(true)}>Change date or time</Button>
          <Button variant="secondary" onClick={() => void handleDelete()}>Delete session</Button>
        </div>
      </div>
      {editing && <EditSession session={s} mutations={mutations} onDone={() => setEditing(false)} />}
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {s.warnings.length > 0 && (
        <section aria-label="Warnings" className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          <ul className="space-y-1">
            {s.warnings.map((w) => (
              <li key={w} className="flex items-start gap-2">
                <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden /> {w}
              </li>
            ))}
          </ul>
        </section>
      )}
      <ExamsSection session={s} mutations={mutations} run={run} />
      <HallsSection session={s} mutations={mutations} run={run} />
    </div>
  )
}

type Run = <T>(action: () => Promise<T>, fallback: string) => Promise<T | undefined>

function EditSession({ session, mutations, onDone }: { session: SessionDetail; mutations: Mutations; onDone: () => void }) {
  const [v, setV] = useState({ date: session.date, label: session.label, startTime: hhmm(session.startTime), endTime: hhmm(session.endTime) })
  const [problem, setProblem] = useState<string | null>(null)
  async function submit(ev: React.FormEvent) {
    ev.preventDefault()
    try {
      await mutations.update.mutateAsync({ ...v, version: session.version })
      onDone()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not save.')
    }
  }
  return (
    <EditPanel title="Change date or time" onClose={onDone}>
      <form onSubmit={(ev) => void submit(ev)} className="space-y-4">
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        <div className="grid gap-4 sm:grid-cols-4">
          <TextField label="Date" type="date" value={v.date} onChange={(e) => setV({ ...v, date: e.target.value })} />
          <TextField label="Label" value={v.label} onChange={(e) => setV({ ...v, label: e.target.value })} />
          <TextField label="Starts" type="time" value={v.startTime} onChange={(e) => setV({ ...v, startTime: e.target.value })} />
          <TextField label="Ends" type="time" value={v.endTime} onChange={(e) => setV({ ...v, endTime: e.target.value })} />
        </div>
        <Button type="submit" loading={mutations.update.isPending}>Save</Button>
      </form>
    </EditPanel>
  )
}

function ExamsSection({ session, mutations, run }: { session: SessionDetail; mutations: Mutations; run: Run }) {
  const [search, setSearch] = useState('')
  const q = useDeferredValue(search.trim())
  const subjects = useSubjects({ q, departmentId: '', includeInactive: false, page: 0, size: 20 })
  const [subjectId, setSubjectId] = useState('')
  const [type, setType] = useState<ExamType>('THEORY')
  const [registered, setRegistered] = useState('')
  const scheduled = new Set(session.exams.map((e) => e.subject.id))
  const options = (subjects.data?.items ?? []).filter((s) => !scheduled.has(s.id))

  async function add() {
    const done = await run(
      () => mutations.addExam.mutateAsync({ subjectId, type, registeredStudents: registered === '' ? null : Number(registered) }),
      'Could not add the exam.',
    )
    if (done) {
      setSubjectId('')
      setRegistered('')
      setSearch('')
    }
  }

  return (
    <Section title="Exams" description="Subjects examined in this session. Registered students are optional and only used to spot unseated students.">
      <Table head={['Subject', 'Department', 'Type', 'Registered', 'Seated', '']} empty={session.exams.length === 0 && 'No exams yet.'}>
        {session.exams.map((e) => (
          <tr key={e.id}>
            <td className="px-4 py-2"><span className="font-medium text-slate-900">{e.subject.code}</span> <span className="text-slate-600">{e.subject.name}</span></td>
            <td className="px-4 py-2 text-slate-600">{e.department.code}</td>
            <td className="px-4 py-2 text-slate-600">{e.type === 'PRACTICAL' ? 'Practical' : 'Theory'}</td>
            <td className="px-4 py-2 text-slate-600">{e.registeredStudents ?? '-'}</td>
            <td className="px-4 py-2 text-slate-600">{e.seatedStudents}</td>
            <td className="px-4 py-2 text-right">
              <button
                type="button"
                className="text-red-700 hover:underline"
                aria-label={`Remove ${e.subject.code}`}
                onClick={() => void run(() => mutations.removeExam.mutateAsync(e.id), 'Could not remove the exam.')}
              >
                Remove
              </button>
            </td>
          </tr>
        ))}
      </Table>
      <div className="grid gap-4 sm:grid-cols-4">
        <TextField label="Find subject" placeholder="Code or name" value={search} onChange={(e) => setSearch(e.target.value)} />
        <SelectField
          label="Subject"
          value={subjectId}
          onChange={(e) => setSubjectId(e.target.value)}
          options={[{ value: '', label: 'Choose...' }, ...options.map((s) => ({ value: s.id, label: `${s.code} - ${s.name}` }))]}
        />
        <SelectField
          label="Type"
          value={type}
          onChange={(e) => setType(e.target.value as ExamType)}
          options={[{ value: 'THEORY', label: 'Theory' }, { value: 'PRACTICAL', label: 'Practical' }]}
        />
        <TextField label="Registered students" type="number" min={0} hint="Optional" value={registered} onChange={(e) => setRegistered(e.target.value)} />
      </div>
      <div>
        <Button type="button" variant="secondary" disabled={!subjectId} loading={mutations.addExam.isPending} onClick={() => void add()}>
          Add exam
        </Button>
      </div>
    </Section>
  )
}

function HallsSection({ session, mutations, run }: { session: SessionDetail; mutations: Mutations; run: Run }) {
  const [editing, setEditing] = useState<SessionHallView | 'new' | null>(null)
  return (
    <Section title="Halls and seating" description="Each hall used in this session, how many students of each exam sit there, and the invigilators it needs.">
      {session.halls.length === 0 && <p className="text-sm text-slate-500">No halls assigned.</p>}
      <ul className="space-y-3">
        {session.halls.map((h) => (
          <li key={h.id} className="rounded-md border border-slate-200 p-3 text-sm">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <span className="font-medium text-slate-900">{h.hall.code}</span> <span className="text-slate-600">{h.hall.name}</span>
                <span className="ml-2 text-slate-500">{h.totalStudents}/{h.hall.capacity} seats</span>
              </div>
              <div className="flex gap-3">
                <button type="button" className="text-indigo-700 hover:underline" aria-label={`Edit ${h.hall.code}`} onClick={() => setEditing(h)}>Edit</button>
                <button
                  type="button"
                  className="text-red-700 hover:underline"
                  aria-label={`Remove ${h.hall.code}`}
                  onClick={() => void run(() => mutations.removeHall.mutateAsync(h.id), 'Could not remove the hall.')}
                >
                  Remove
                </button>
              </div>
            </div>
            <p className="mt-1 text-slate-600">{h.seating.map((s) => `${s.subjectCode}: ${s.studentCount}`).join(' · ') || 'No students seated'}</p>
            <p className="mt-1 text-slate-800">
              <strong>{h.staffing.total}</strong> invigilator{h.staffing.total === 1 ? '' : 's'}
              {h.staffing.chief > 0 && ' (incl. chief)'} <span className="text-slate-500">- {h.staffing.basis}</span>
            </p>
          </li>
        ))}
      </ul>
      {editing ? (
        <HallForm key={editing === 'new' ? 'new' : editing.id} session={session} hall={editing === 'new' ? null : editing} mutations={mutations} onDone={() => setEditing(null)} />
      ) : (
        <div>
          <Button type="button" variant="secondary" onClick={() => setEditing('new')} disabled={session.exams.length === 0}>
            Add hall
          </Button>
          {session.exams.length === 0 && <p className="mt-1 text-xs text-slate-500">Add exams first, then seat their students in halls.</p>}
        </div>
      )}
    </Section>
  )
}

function HallForm({ session, hall, mutations, onDone }: { session: SessionDetail; hall: SessionHallView | null; mutations: Mutations; onDone: () => void }) {
  const halls = useHalls()
  const used = new Set(session.halls.map((h) => h.hall.id))
  const [hallId, setHallId] = useState(hall?.hall.id ?? '')
  const [counts, setCounts] = useState<Record<string, string>>(
    Object.fromEntries((hall?.seating ?? []).map((s) => [s.examId, String(s.studentCount)])),
  )
  const [override, setOverride] = useState(hall?.requiredOverride != null)
  const [overrideCount, setOverrideCount] = useState(String(hall?.requiredOverride ?? 1))
  const [reason, setReason] = useState(hall?.overrideReason ?? '')
  const [problem, setProblem] = useState<string | null>(null)
  const selected = (halls.data ?? []).find((h) => h.id === hallId)
  const capacity = selected?.capacity ?? hall?.hall.capacity
  const total = Object.values(counts).reduce((sum, v) => sum + (Number(v) || 0), 0)

  async function submit(ev: React.FormEvent) {
    ev.preventDefault()
    setProblem(null)
    const body = {
      requiredOverride: override ? Number(overrideCount) : null,
      overrideReason: override ? reason : null,
      seating: Object.entries(counts)
        .filter(([, v]) => Number(v) > 0)
        .map(([examId, v]) => ({ examId, studentCount: Number(v) })),
    }
    try {
      if (hall) await mutations.updateHall.mutateAsync({ ...body, id: hall.id, version: hall.version })
      else await mutations.addHall.mutateAsync({ ...body, hallId })
      onDone()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not save the hall.')
    }
  }

  return (
    <EditPanel title={hall ? `Seating in ${hall.hall.code}` : 'Add hall'} onClose={onDone}>
      <form onSubmit={(ev) => void submit(ev)} className="space-y-4">
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        {!hall && (
          <SelectField
            label="Hall"
            value={hallId}
            onChange={(e) => setHallId(e.target.value)}
            options={[
              { value: '', label: 'Choose...' },
              ...(halls.data ?? []).filter((h) => !used.has(h.id)).map((h) => ({ value: h.id, label: `${h.code} - ${h.name} (${h.capacity} seats${h.lab ? ', lab' : ''})` })),
            ]}
          />
        )}
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium text-slate-700">Students seated per exam</legend>
          {session.exams.map((e) => (
            <TextField
              key={e.id}
              label={`${e.subject.code} ${e.subject.name}`}
              type="number"
              min={0}
              value={counts[e.id] ?? ''}
              onChange={(ev) => setCounts({ ...counts, [e.id]: ev.target.value })}
            />
          ))}
          <p className="text-sm text-slate-600">
            Total {total}
            {capacity !== undefined && ` of ${capacity} seats`}
          </p>
        </fieldset>
        <CheckboxField
          label="Set the number of invigilators manually"
          description="Overrides the computed number for this hall only. A reason is required."
          checked={override}
          onChange={(e) => setOverride(e.target.checked)}
        />
        {override && (
          <div className="grid gap-4 sm:grid-cols-3">
            <TextField label="Invigilators" type="number" min={0} max={20} value={overrideCount} onChange={(e) => setOverrideCount(e.target.value)} />
            <div className="sm:col-span-2">
              <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
          </div>
        )}
        <div className="flex gap-2">
          <Button type="submit" disabled={!hall && !hallId} loading={mutations.addHall.isPending || mutations.updateHall.isPending}>
            {hall ? 'Save seating' : 'Add hall'}
          </Button>
          <Button type="button" variant="secondary" onClick={onDone}>Cancel</Button>
        </div>
      </form>
    </EditPanel>
  )
}

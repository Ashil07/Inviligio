import { useDeferredValue, useState } from 'react'
import { ErrorAlert, TextField } from '@/components/ui/form'
import { Section, SelectField, SuccessNotice } from '@/components/ui/fields'
import { PageHeader, Table } from '@/components/ui/layout'
import { useSession } from '@/features/auth/useAuth'
import { useFacultySearch } from '@/features/faculty/api'
import { usePolicy } from '@/features/policy/api'
import { formatDate, hhmm, usePeriods } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import {
  useAvailabilityList,
  useCancelAvailability,
  useMyAvailability,
  usePeriodAvailability,
  useRecordAvailability,
  useRequestAvailability,
  useReviewAvailability,
  type AvailabilityRequest,
} from './api'
import { RequestForm } from './RequestForm'
import { RequestsTable } from './RequestsTable'

const today = () => new Date().toISOString().slice(0, 10)

/** Faculty: tell the exam cell when you cannot invigilate. */
export function MyAvailabilityPage() {
  const session = useSession()
  const policy = usePolicy()
  const mine = useMyAvailability()
  const request = useRequestAvailability()
  const cancel = useCancelAvailability()
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  if (!session.facultyId) {
    return (
      <main className="mx-auto max-w-4xl px-4 py-8">
        <PageHeader title="My availability" />
        <p className="mt-4 text-sm text-slate-600">Your login is not linked to a faculty record, so there is nothing to request.</p>
      </main>
    )
  }
  const rules = policy.data?.settings.availability

  async function handleCancel(r: AvailabilityRequest) {
    if (!window.confirm('Withdraw this request?')) return
    setProblem(null)
    try {
      await cancel.mutateAsync(r.id)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not cancel.')
    }
  }

  return (
    <main className="mx-auto max-w-4xl space-y-6 px-4 py-8">
      <PageHeader
        title="My availability"
        description="Approved leave and unavailability are always respected when duties are allocated."
      />
      {rules && (
        <Section
          title="New request"
          description={rules.leaveRequiresHodApproval ? 'Your head of department will review the request.' : 'Requests take effect immediately.'}
        >
          {notice && <SuccessNotice>{notice}</SuccessNotice>}
          <RequestForm
            allowDays={rules.allowFullDayUnavailability}
            allowSession={rules.allowSessionUnavailability}
            submitLabel="Send request"
            busy={request.isPending}
            onSubmit={async (body) => {
              setNotice(null)
              const saved = await request.mutateAsync(body)
              setNotice(saved.status === 'APPROVED' ? 'Saved. You will not be given duties then.' : 'Sent for approval.')
            }}
          />
        </Section>
      )}
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {mine.error && <ErrorAlert>{toApiProblem(mine.error).detail ?? 'Could not load your requests.'}</ErrorAlert>}
      <section>
        <h2 className="mb-3 text-lg font-semibold text-slate-900">My requests</h2>
        <RequestsTable
          requests={mine.data ?? []}
          showFaculty={false}
          onCancel={(r) => void handleCancel(r)}
          canCancel={(r) => r.toDate >= today()}
        />
      </section>
    </main>
  )
}

function StatusFilter({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="max-w-xs">
      <SelectField
        label="Show"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        options={[
          { value: 'PENDING', label: 'Waiting for review' },
          { value: 'APPROVED', label: 'Approved' },
          { value: 'REJECTED', label: 'Rejected' },
          { value: 'CANCELLED', label: 'Cancelled' },
          { value: '', label: 'All' },
        ]}
      />
    </div>
  )
}

function useReviewer() {
  const review = useReviewAvailability()
  const [problem, setProblem] = useState<string | null>(null)
  async function onReview(r: AvailabilityRequest, approve: boolean) {
    const note = window.prompt(approve ? 'Note for the faculty member (optional)' : 'Why is it rejected? (optional)')
    if (note === null) return
    setProblem(null)
    try {
      await review.mutateAsync({ id: r.id, approve, note: note || undefined })
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not save the decision.')
    }
  }
  return { onReview, problem }
}

/** HOD: requests from faculty of their department. */
export function DepartmentRequestsPage() {
  const [status, setStatus] = useState('PENDING')
  const list = useAvailabilityList(status)
  const { onReview, problem } = useReviewer()
  return (
    <main className="mx-auto max-w-6xl space-y-6 px-4 py-8">
      <PageHeader title="Leave requests" description="Requests from faculty of your department. Approved requests are respected by allocation." />
      <StatusFilter value={status} onChange={setStatus} />
      {(problem || list.error) && <ErrorAlert>{problem ?? toApiProblem(list.error).detail ?? 'Could not load requests.'}</ErrorAlert>}
      <RequestsTable requests={list.data ?? []} showFaculty onReview={(r, a) => void onReview(r, a)} />
    </main>
  )
}

/** Exam cell: every request, recording leave for anyone, and who is unavailable per session. */
export function AdminAvailabilityPage() {
  const [status, setStatus] = useState('PENDING')
  const list = useAvailabilityList(status)
  const { onReview, problem } = useReviewer()
  const cancel = useCancelAvailability()
  const [cancelProblem, setCancelProblem] = useState<string | null>(null)

  async function handleCancel(r: AvailabilityRequest) {
    if (!window.confirm(`Cancel ${r.faculty.name}'s request?`)) return
    setCancelProblem(null)
    try {
      await cancel.mutateAsync(r.id)
    } catch (e) {
      setCancelProblem(toApiProblem(e).detail ?? 'Could not cancel.')
    }
  }

  return (
    <div className="space-y-8">
      <PageHeader title="Availability and leave" description="Approved leave and unavailability are hard rules for allocation. Pending requests are only warnings." />
      <RecordLeave />
      <section className="space-y-4">
        <h2 className="text-lg font-semibold text-slate-900">Requests</h2>
        <StatusFilter value={status} onChange={setStatus} />
        {(problem || cancelProblem || list.error) && (
          <ErrorAlert>{problem ?? cancelProblem ?? toApiProblem(list.error).detail ?? 'Could not load requests.'}</ErrorAlert>
        )}
        <RequestsTable requests={list.data ?? []} showFaculty onReview={(r, a) => void onReview(r, a)} onCancel={(r) => void handleCancel(r)} />
      </section>
      <PeriodOverview />
    </div>
  )
}

function RecordLeave() {
  const [search, setSearch] = useState('')
  const q = useDeferredValue(search.trim())
  const faculty = useFacultySearch({ q, departmentId: '', employmentType: '', includeInactive: false, page: 0, size: 20 })
  const [facultyId, setFacultyId] = useState('')
  const record = useRecordAvailability()
  const [notice, setNotice] = useState<string | null>(null)
  return (
    <Section title="Record leave" description="For leave reported to the exam cell. It takes effect immediately, whatever the self-service rules.">
      {notice && <SuccessNotice>{notice}</SuccessNotice>}
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Find faculty" placeholder="Name, code or email" value={search} onChange={(e) => setSearch(e.target.value)} />
        <SelectField
          label="Faculty member"
          value={facultyId}
          onChange={(e) => setFacultyId(e.target.value)}
          options={[{ value: '', label: 'Choose...' }, ...(faculty.data?.items ?? []).map((f) => ({ value: f.id, label: `${f.fullName} (${f.employeeCode})` }))]}
        />
      </div>
      <RequestForm
        allowDays
        allowSession
        submitLabel="Record"
        busy={record.isPending}
        onSubmit={async (body) => {
          setNotice(null)
          if (!facultyId) throw new Error('Choose a faculty member.')
          const saved = await record.mutateAsync({ ...body, facultyId })
          setNotice(`Recorded for ${saved.faculty.name}.`)
        }}
      />
    </Section>
  )
}

function PeriodOverview() {
  const periods = usePeriods()
  const [periodId, setPeriodId] = useState('')
  const overview = usePeriodAvailability(periodId)
  return (
    <Section title="Who is unavailable, per session">
      <div className="max-w-sm">
        <SelectField
          label="Examination period"
          value={periodId}
          onChange={(e) => setPeriodId(e.target.value)}
          options={[{ value: '', label: 'Choose...' }, ...(periods.data ?? []).map((p) => ({ value: p.id, label: p.name }))]}
        />
      </div>
      {overview.error && <ErrorAlert>{toApiProblem(overview.error).detail ?? 'Could not load the overview.'}</ErrorAlert>}
      {overview.data && (
        <Table head={['Session', 'Unavailable', 'Pending']} empty={overview.data.length === 0 && 'This period has no sessions.'}>
          {overview.data.map((row) => (
            <tr key={row.session.id}>
              <td className="whitespace-nowrap px-4 py-2 text-slate-700">
                {formatDate(row.session.date)} {row.session.label} {hhmm(row.session.startTime)}
              </td>
              <td className="px-4 py-2 text-slate-700">
                {row.unavailable.length === 0 ? '-' : row.unavailable.map((u) => `${u.faculty.name}${u.wholeDay ? '' : ' (session)'}`).join(', ')}
              </td>
              <td className="px-4 py-2 text-amber-800">{row.pending.map((p) => p.name).join(', ') || '-'}</td>
            </tr>
          ))}
        </Table>
      )}
    </Section>
  )
}

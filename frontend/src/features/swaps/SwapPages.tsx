import { zodResolver } from '@hookform/resolvers/zod'
import { AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useSearchParams } from 'react-router'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { Section, SelectField, SuccessNotice } from '@/components/ui/fields'
import { PageHeader, Table } from '@/components/ui/layout'
import { useMyDuties } from '@/features/myduties/api'
import { usePolicy } from '@/features/policy/api'
import { toApiProblem } from '@/lib/api/client'
import { formatDateTime } from '@/lib/forms'
import {
  STATUS_LABEL,
  describeDuty,
  STATUS_STYLE,
  useCheckSwap,
  useMySwaps,
  useRequestSwap,
  useSwapAction,
  useSwapList,
  useSwapOptions,
  type Swap,
  type SwapAction,
  type SwapInput,
  type SwapStatus,
} from './api'

function StatusBadge({ status }: { status: SwapStatus }) {
  return <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLE[status]}`}>{STATUS_LABEL[status]}</span>
}

/** What happens in the swap, in one sentence. */
function swapSentence(s: Swap) {
  return s.targetDuty
    ? `${s.requester.name} takes ${describeDuty(s.targetDuty)}; ${s.target.name} takes ${describeDuty(s.requesterDuty)}.`
    : `${s.target.name} takes ${describeDuty(s.requesterDuty)} from ${s.requester.name}.`
}

function outcome(s: Swap) {
  if (s.status === 'INVALIDATED') return s.invalidReason
  if (s.status === 'APPROVED') return s.appliedAt ? `Done ${formatDateTime(s.appliedAt)}.` : 'Done.'
  if (s.status === 'REJECTED') return s.reviewNote ?? s.recipientNote ?? (s.reviewedByName ? `Rejected by ${s.reviewedByName}.` : 'Declined.')
  return null
}

// ---- faculty ----

const swapSchema = z.object({
  assignmentId: z.string().min(1, 'Choose one of your duties'),
  targetFacultyId: z.string().min(1, 'Choose a colleague'),
  exchange: z.string(),
  reason: z.string().trim().max(500, 'Keep the reason under 500 characters'),
})
type SwapValues = z.infer<typeof swapSchema>

/** Faculty: ask a colleague to take a duty (or trade one), answer requests, follow your own. */
export function FacultySwapsPage() {
  const policy = usePolicy()
  const enabled = policy.data?.settings.swaps.swapsEnabled ?? true
  return (
    <main className="mx-auto max-w-6xl space-y-6 px-4 py-8">
      <PageHeader
        title="Duty swaps"
        description="Hand a duty to a colleague or trade one with them. Every swap is checked against the college's allocation rules before it happens."
      />
      {!enabled ? (
        <p className="flex items-start gap-2 rounded-md border border-slate-200 bg-white p-4 text-sm text-slate-700">
          <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
          Your college does not allow duty swaps. Ask the examination cell if a duty has to change.
        </p>
      ) : (
        <RequestSwap />
      )}
      <ReceivedAndSent />
    </main>
  )
}

function RequestSwap() {
  const [params] = useSearchParams()
  const duties = useMyDuties()
  const request = useRequestSwap()
  const [sent, setSent] = useState<Swap | null>(null)
  const mine = (duties.data?.upcoming ?? []).filter((d) => d.status === 'ASSIGNED')
  const preselected = mine.some((d) => d.assignmentId === params.get('duty')) ? (params.get('duty') ?? '') : ''

  const form = useForm<SwapValues>({
    resolver: zodResolver(swapSchema),
    defaultValues: { assignmentId: '', targetFacultyId: '', exchange: '', reason: '' },
  })
  // A duty picked from the dashboard ("Swap" link) is chosen once the duties have loaded.
  const applied = useRef(false)
  useEffect(() => {
    if (preselected && !applied.current) {
      applied.current = true
      form.setValue('assignmentId', preselected)
    }
  }, [preselected, form])
  const [assignmentId, targetFacultyId, exchange] = useWatch({ control: form.control, name: ['assignmentId', 'targetFacultyId', 'exchange'] })
  const options = useSwapOptions(assignmentId)
  const colleague = options.data?.colleagues.find((c) => c.faculty.id === targetFacultyId)
  // A colleague who cannot take the duty outright must offer one of theirs back.
  const ready = !!colleague && (colleague.canTakeHandover || !!exchange)
  const input: SwapInput | null = assignmentId && ready ? { assignmentId, targetFacultyId, targetAssignmentId: exchange || null } : null
  const check = useCheckSwap(input)

  async function onSubmit(values: SwapValues) {
    setSent(null)
    // A failure is shown from request.error; catching keeps it from becoming an unhandled rejection.
    const swap = await request
      .mutateAsync({
        assignmentId: values.assignmentId,
        targetFacultyId: values.targetFacultyId,
        targetAssignmentId: values.exchange || null,
        reason: values.reason || undefined,
      })
      .catch(() => null)
    if (!swap) return
    setSent(swap)
    form.reset({ assignmentId: '', targetFacultyId: '', exchange: '', reason: '' })
  }

  return (
    <Section
      title="Ask for a swap"
      description="Colleagues who could take your duty under the rules come first; the others can only trade one of their duties."
    >
      {sent && (
        <SuccessNotice>
          {sent.status === 'PENDING_RECIPIENT' && `Sent to ${sent.target.name}. The swap happens once they accept.`}
          {sent.status === 'PENDING_HOD' && 'Sent to your head of department for approval.'}
          {sent.status === 'APPROVED' && `Done: ${swapSentence(sent)}`}
          {sent.status === 'INVALIDATED' && `The swap could not be made: ${sent.invalidReason}`}
        </SuccessNotice>
      )}
      {duties.isPending ? (
        <p className="text-sm text-slate-600">Loading your duties...</p>
      ) : mine.length === 0 ? (
        <p className="text-sm text-slate-600">You have no upcoming duties in a published chart.</p>
      ) : (
        <form className="grid gap-4 sm:grid-cols-2" onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <SelectField
            label="Your duty"
            error={form.formState.errors.assignmentId?.message}
            options={[{ value: '', label: 'Choose a duty' }, ...mine.map((d) => ({ value: d.assignmentId, label: describeDuty(d) }))]}
            {...form.register('assignmentId', {
              onChange: () => {
                form.setValue('targetFacultyId', '')
                form.setValue('exchange', '')
              },
            })}
          />
          <SelectField
            label="Colleague"
            disabled={!assignmentId || options.isPending}
            error={form.formState.errors.targetFacultyId?.message}
            description={assignmentId && options.data?.colleagues.length === 0 ? 'Nobody else can take this duty under the rules.' : undefined}
            options={[
              { value: '', label: assignmentId && options.isPending ? 'Loading colleagues...' : 'Choose a colleague' },
              ...(options.data?.colleagues ?? []).map((c) => ({
                value: c.faculty.id,
                label: `${c.faculty.name} (${c.faculty.code}, ${c.faculty.departmentCode}) · ${c.duties} ${c.duties === 1 ? 'duty' : 'duties'}${c.canTakeHandover ? '' : ' · exchange only'}`,
              })),
            ]}
            {...form.register('targetFacultyId', { onChange: () => form.setValue('exchange', '') })}
          />
          <SelectField
            label="In return"
            disabled={!colleague}
            description={
              colleague && !colleague.canTakeHandover
                ? `${colleague.faculty.name} cannot take your duty on top of theirs, so choose one of their duties to take in exchange.`
                : 'A handover gives your duty away; or take one of their duties in exchange.'
            }
            options={[
              { value: '', label: colleague && !colleague.canTakeHandover ? 'Choose one of their duties' : 'Nothing: hand over my duty' },
              ...(colleague?.exchangeable ?? []).map((d) => ({ value: d.assignmentId, label: `Their duty: ${describeDuty(d)}` })),
            ]}
            {...form.register('exchange')}
          />
          <TextField label="Reason (optional)" error={form.formState.errors.reason?.message} {...form.register('reason')} />

          <div className="sm:col-span-2 space-y-3">
            {options.error && <ErrorAlert>{toApiProblem(options.error).detail ?? 'Could not load colleagues.'}</ErrorAlert>}
            {input && <Verdict loading={check.isFetching} check={check.data} error={check.error} />}
            {request.error && <ErrorAlert>{toApiProblem(request.error).detail ?? 'Could not send the request.'}</ErrorAlert>}
            <Button type="submit" loading={request.isPending} disabled={!input || !check.data?.allowed}>
              Ask for this swap
            </Button>
          </div>
        </form>
      )}
    </Section>
  )
}

function Verdict({ loading, check, error }: { loading: boolean; check?: { allowed: boolean; blocking: { code: string; message: string }[]; warnings: string[] }; error: unknown }) {
  if (error) return <ErrorAlert>{toApiProblem(error).detail ?? 'Could not check this swap.'}</ErrorAlert>
  if (loading || !check) return <p className="text-sm text-slate-500">Checking the rules...</p>
  return (
    <div className="space-y-2 text-sm" role="status">
      {check.allowed ? (
        <p className="flex items-center gap-2 text-emerald-700">
          <CheckCircle2 className="size-4" aria-hidden /> This swap fits the rules.
        </p>
      ) : (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-red-800">
          <p className="font-medium">This swap breaks a strict rule:</p>
          <ul className="mt-1 list-disc pl-5">
            {check.blocking.map((b, i) => (
              <li key={i}>{b.message}</li>
            ))}
          </ul>
        </div>
      )}
      {check.warnings.length > 0 && (
        <div className="flex items-start gap-2 text-amber-800">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden />
          <span>{check.warnings.join('; ')}</span>
        </div>
      )}
    </div>
  )
}

function ReceivedAndSent() {
  const swaps = useMySwaps()
  const act = useSwapAction()
  const [problem, setProblem] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)

  async function run(swap: Swap, action: SwapAction) {
    if (action === 'decline' && !window.confirm(`Decline ${swap.requester.name}'s request?`)) return
    if (action === 'cancel' && !window.confirm('Cancel this swap request?')) return
    setProblem(null)
    setResult(null)
    try {
      const after = await act.mutateAsync({ swap, action })
      if (after.status === 'INVALIDATED') setResult(`The swap could not be made: ${after.invalidReason}`)
      else if (after.status === 'APPROVED') setResult(`Done: ${swapSentence(after)}`)
      else if (after.status === 'PENDING_HOD') setResult('Accepted. The swap now needs the head of department.')
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not update the request.')
    }
  }

  if (swaps.isPending) return <p className="text-sm text-slate-600">Loading swap requests...</p>
  if (swaps.error) {
    const p = toApiProblem(swaps.error)
    return p.code === 'NOT_A_FACULTY_MEMBER' ? null : <ErrorAlert>{p.detail ?? 'Could not load swap requests.'}</ErrorAlert>
  }
  const waiting = swaps.data.received.filter((s) => s.status === 'PENDING_RECIPIENT')
  const earlier = swaps.data.received.filter((s) => s.status !== 'PENDING_RECIPIENT')
  return (
    <>
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {result && <SuccessNotice>{result}</SuccessNotice>}
      <Section title="Waiting for your answer">
        {waiting.length === 0 ? (
          <p className="text-sm text-slate-600">Nobody has asked you to swap.</p>
        ) : (
          <ul className="space-y-3">
            {waiting.map((s) => (
              <li key={s.id} className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-slate-800">
                <p className="font-medium">{s.requester.name} asks you to swap</p>
                <p className="mt-1">{swapSentence(s)}</p>
                {s.reason && <p className="mt-1 text-slate-600">Reason: {s.reason}</p>}
                <div className="mt-2 flex gap-2">
                  <Button onClick={() => void run(s, 'accept')} loading={act.isPending && act.variables?.swap.id === s.id && act.variables.action === 'accept'}>
                    Accept
                  </Button>
                  <Button variant="secondary" onClick={() => void run(s, 'decline')}>
                    Decline
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section title="Your requests">
        <SwapTable swaps={swaps.data.sent} empty="You have not asked for any swaps." action={(s) => s.status === 'PENDING_RECIPIENT' || s.status === 'PENDING_HOD' ? (
          <Button variant="secondary" onClick={() => void run(s, 'cancel')}>
            Cancel
          </Button>
        ) : null} />
      </Section>
      {earlier.length > 0 && (
        <Section title="Earlier requests to you">
          <SwapTable swaps={earlier} empty="" />
        </Section>
      )}
    </>
  )
}

function SwapTable({ swaps, empty, action }: { swaps: Swap[]; empty: string; action?: (s: Swap) => ReactNode }) {
  return (
    <Table head={['Asked', 'Swap', 'Status', ...(action ? [''] : [])]} empty={swaps.length === 0 && empty}>
      {swaps.map((s) => (
        <tr key={s.id}>
          <td className="whitespace-nowrap px-4 py-2 text-slate-600">{formatDateTime(s.createdAt)}</td>
          <td className="px-4 py-2 text-slate-800">
            {swapSentence(s)}
            {outcome(s) && <span className="block text-xs text-slate-500">{outcome(s)}</span>}
          </td>
          <td className="px-4 py-2">
            <StatusBadge status={s.status} />
          </td>
          {action && <td className="px-4 py-2 text-right">{action(s)}</td>}
        </tr>
      ))}
    </Table>
  )
}

// ---- HOD and exam cell ----

const STATUS_FILTER = [
  { value: 'PENDING_HOD', label: 'Waiting for approval' },
  { value: 'PENDING_RECIPIENT', label: 'Waiting for colleague' },
  { value: 'APPROVED', label: 'Done' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'INVALIDATED', label: 'No longer possible' },
  { value: 'CANCELLED', label: 'Cancelled' },
  { value: '', label: 'All' },
]

/** HOD (own department) and exam cell (everyone): swaps waiting for approval, and their history. */
export function SwapApprovalsPage({ description }: { description: string }) {
  const [status, setStatus] = useState('PENDING_HOD')
  const list = useSwapList(status)
  const act = useSwapAction()
  const [problem, setProblem] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)

  async function decide(swap: Swap, action: 'approve' | 'reject') {
    if (action === 'reject' && !window.confirm(`Reject the swap between ${swap.requester.name} and ${swap.target.name}?`)) return
    setProblem(null)
    setResult(null)
    try {
      const after = await act.mutateAsync({ swap, action })
      setResult(
        after.status === 'APPROVED'
          ? `Approved and done: ${swapSentence(after)}`
          : after.status === 'INVALIDATED'
            ? `The swap could not be made: ${after.invalidReason}`
            : 'Rejected.',
      )
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not update the request.')
    }
  }

  return (
    <main className="mx-auto max-w-6xl space-y-6 px-4 py-8">
      <PageHeader title="Swap requests" description={description} />
      <div className="w-64">
        <SelectField label="Show" value={status} onChange={(e) => setStatus(e.target.value)} options={STATUS_FILTER} />
      </div>
      {(problem || list.error) && <ErrorAlert>{problem ?? toApiProblem(list.error).detail ?? 'Could not load swap requests.'}</ErrorAlert>}
      {result && <SuccessNotice>{result}</SuccessNotice>}
      <Table head={['Asked', 'Who', 'Swap', 'Reason', 'Status', '']} empty={!list.isPending && (list.data ?? []).length === 0 && 'No swap requests here.'}>
        {(list.data ?? []).map((s) => (
          <tr key={s.id}>
            <td className="whitespace-nowrap px-4 py-2 text-slate-600">{formatDateTime(s.createdAt)}</td>
            <td className="px-4 py-2 text-slate-800">
              {s.requester.name} ({s.requester.departmentCode}) → {s.target.name} ({s.target.departmentCode})
            </td>
            <td className="px-4 py-2 text-slate-800">
              {swapSentence(s)}
              {outcome(s) && <span className="block text-xs text-slate-500">{outcome(s)}</span>}
            </td>
            <td className="px-4 py-2 text-slate-600">{s.reason ?? '-'}</td>
            <td className="px-4 py-2">
              <StatusBadge status={s.status} />
            </td>
            <td className="px-4 py-2 text-right">
              {s.status === 'PENDING_HOD' && (
                <div className="flex justify-end gap-2">
                  <Button onClick={() => void decide(s, 'approve')} loading={act.isPending && act.variables?.swap.id === s.id && act.variables.action === 'approve'}>
                    Approve
                  </Button>
                  <Button variant="secondary" onClick={() => void decide(s, 'reject')}>
                    Reject
                  </Button>
                </div>
              )}
            </td>
          </tr>
        ))}
      </Table>
    </main>
  )
}

export function HodSwapsPage() {
  return <SwapApprovalsPage description="Swaps asked for by faculty of your department. Approving applies the swap after checking it against the rules again." />
}

export function AdminSwapsPage() {
  return <SwapApprovalsPage description="Every swap request in the college. Approving applies the swap after checking it against the rules again." />
}

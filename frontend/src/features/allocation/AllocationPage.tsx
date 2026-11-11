import { AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { Button, ErrorAlert } from '@/components/ui/form'
import { Section, SelectField, SuccessNotice } from '@/components/ui/fields'
import { PageHeader, Table } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { usePolicy } from '@/features/policy/api'
import { formatDate, hhmm, usePeriod } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import { formatDateTime } from '@/lib/forms'
import {
  REASON_LABEL,
  explain,
  sessionEnded,
  useMarkAttendance,
  useChartHistory,
  useChartVersion,
  useChartVersions,
  useDutyChart,
  useGenerateChart,
  useNewDraft,
  usePublishChart,
  type ChartSession,
  type Duty,
  type DutyChart,
  type Unfilled,
} from './api'
import { ChartEditor, type EditTarget } from './ChartEditor'
import { SubstitutePanel, type SubstituteTarget } from './SubstitutePanel'

/** Generate, edit, review and publish the period's duty chart. */
export function AllocationPage() {
  const periodId = useParams().periodId!
  const period = usePeriod(periodId)
  const chart = useDutyChart(periodId)
  const generate = useGenerateChart(periodId)
  const newDraft = useNewDraft(periodId)
  const [problem, setProblem] = useState<string | null>(null)

  if (period.error) return <ErrorAlert>{toApiProblem(period.error).detail ?? 'Could not load the period.'}</ErrorAlert>
  if (!period.data || chart.isPending) return <FullPageSpinner label="Loading duty chart" />

  const c = chart.data
  const draft = c?.status === 'DRAFT'

  async function run() {
    if (draft && !window.confirm('Generate again? The current draft, including its explanations and edits, will be replaced.')) return
    setProblem(null)
    try {
      await generate.mutateAsync()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Allocation failed.')
    }
  }

  async function copyPublished() {
    setProblem(null)
    try {
      await newDraft.mutateAsync()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not start a new version.')
    }
  }

  const actions = (
    <div className="flex flex-wrap gap-2">
      {c && !draft && (
        <Button loading={newDraft.isPending} onClick={() => void copyPublished()}>
          Start new version
        </Button>
      )}
      <Button variant={c && !draft ? 'secondary' : 'primary'} loading={generate.isPending} onClick={() => void run()}>
        {!c ? 'Generate duty chart' : draft ? 'Generate again' : 'Generate new draft'}
      </Button>
    </div>
  )

  return (
    <div className="space-y-6">
      <Link to={`/admin/exams/${periodId}`} className="text-sm link">← {period.data.name}</Link>
      <PageHeader
        title="Duty allocation"
        description="Invigilo assigns faculty to every hall using the college policy. Strict rules are never broken; positions it cannot fill are listed with the reasons."
        actions={actions}
      />
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {chart.error && <ErrorAlert>{toApiProblem(chart.error).detail ?? 'Could not load the chart.'}</ErrorAlert>}
      {!c && !chart.error && (
        <p className="text-sm text-neutral-600">No duty chart yet. Add sessions, exams and seating, then generate.</p>
      )}
      {c && <ChartReview chart={c} />}
      {c && <Versions periodId={periodId} />}
    </div>
  )
}

/** One chart version, read-only (published, superseded or the current draft). */
export function ChartVersionPage() {
  const { periodId, chartVersion } = useParams()
  const chart = useChartVersion(periodId!, Number(chartVersion))
  if (chart.isPending) return <FullPageSpinner label="Loading chart version" />
  return (
    <div className="space-y-6">
      <Link to={`/admin/exams/${periodId}/allocation`} className="text-sm link">← Duty allocation</Link>
      <PageHeader title={`Duty chart version ${chartVersion}`} description="A read-only view of this version." />
      {chart.error && <ErrorAlert>{toApiProblem(chart.error).detail ?? 'Could not load this version.'}</ErrorAlert>}
      {chart.data && <ChartReview chart={chart.data} readOnly />}
    </div>
  )
}

const STATUS_LABEL = { DRAFT: 'Draft', PUBLISHED: 'Published', SUPERSEDED: 'Replaced' } as const

function ChartReview({ chart, readOnly = false }: { chart: DutyChart; readOnly?: boolean }) {
  const s = chart.stats
  const unfilledTotal = chart.unfilled.reduce((n, u) => n + u.missing, 0)
  const editable = chart.status === 'DRAFT' && !readOnly
  const [editing, setEditing] = useState<EditTarget | null>(null)
  const edit = editable ? setEditing : undefined
  // Substitutes go into the published chart, also from its version page while a draft is current.
  const policy = usePolicy()
  const substitutable = chart.status === 'PUBLISHED' && (policy.data?.settings.swaps.adminCanReassignDirectly ?? false)
  const [substituting, setSubstituting] = useState<SubstituteTarget | null>(null)
  const [notice, setNotice] = useState('')
  const attendance = useMarkAttendance(chart.periodId)
  const [attendanceProblem, setAttendanceProblem] = useState<string | null>(null)

  /** After the session the exam cell can mark a no-show absent (not paid), or undo it. */
  async function markAttendance(duty: Duty, absent: boolean) {
    const question = absent
      ? `Mark ${duty.facultyName} absent? The duty will not be paid.`
      : `Mark ${duty.facultyName} present again? The duty will be paid.`
    if (!window.confirm(question)) return
    setAttendanceProblem(null)
    setNotice('')
    try {
      await attendance.mutateAsync({ assignmentId: duty.assignmentId, absent, version: duty.version })
      setNotice(absent ? `${duty.facultyName} is marked absent.` : `${duty.facultyName} is marked present.`)
    } catch (e) {
      setAttendanceProblem(toApiProblem(e).detail ?? 'Could not change the attendance.')
    }
  }

  /** Opens the replace panel for a duty listed as an issue. */
  function replaceIssue(assignmentId: string) {
    for (const session of chart.sessions) {
      for (const hall of session.halls) {
        const duty = hall.duties.find((d) => d.assignmentId === assignmentId)
        if (duty) return setEditing({ kind: 'replace', session, hall, duty })
      }
    }
  }

  return (
    <div className="space-y-6">
      <div className="space-y-1 text-sm text-neutral-600">
        <p>
          <span className="font-medium text-neutral-900">
            {STATUS_LABEL[chart.status]} version {chart.chartVersion}
          </span>
          , created {formatDateTime(chart.generatedAt)}
          {chart.policyVersion != null ? ` with policy version ${chart.policyVersion}` : ''}.
          {chart.status === 'PUBLISHED' && chart.publishedAt && ` Published ${formatDateTime(chart.publishedAt)}${chart.publishedBy ? ` by ${chart.publishedBy}` : ''}; this is what faculty see.`}
        </p>
        {chart.status === 'DRAFT' && chart.publishedVersion != null && (
          <p>
            Version {chart.publishedVersion} is published and stays in force until you publish this draft.{' '}
            <Link to={`/admin/exams/${chart.periodId}/allocation/versions/${chart.publishedVersion}`} className="font-medium link">
              Open version {chart.publishedVersion}
            </Link>{' '}
            to choose substitutes for it.
          </p>
        )}
        {editable && <p>You can change any duty below; every change is checked against the same rules as generation.</p>}
        {chart.status === 'PUBLISHED' && (
          <p>
            Duties of a published chart change only through approved swaps{substitutable ? ' and substitutes you choose below' : ''}. For bigger
            changes, start a new version, edit it and publish it.
          </p>
        )}
      </div>
      {chart.issues.length > 0 && (
        <div role="alert" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-900">
          <p className="flex items-center gap-2 font-medium">
            <AlertTriangle className="size-4" aria-hidden />
            {chart.issues.length} dut{chart.issues.length === 1 ? 'y breaks' : 'ies break'} a strict rule now
          </p>
          <ul className="mt-2 space-y-1">
            {chart.issues.map((i) => (
              <li key={i.assignmentId} className="flex flex-wrap items-center gap-2">
                <span>
                  {formatDate(i.date)} {i.sessionLabel} · {i.hallCode}: {i.facultyName} ({i.reasons.map((r) => REASON_LABEL[r] ?? r).join(', ')})
                </span>
                {editable && (
                  <button type="button" className="font-medium text-red-800 underline" onClick={() => replaceIssue(i.assignmentId)}>
                    Replace
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {(chart.inputsChanged || chart.policyChanged) && (
        <div role="status" className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
          <div>
            {chart.inputsChanged && <p>The timetable, faculty or availability changed after this draft was generated.</p>}
            {chart.policyChanged && <p>The examination policy has a newer version than the one this draft used.</p>}
            <p>{chart.status === 'DRAFT' ? 'Generate again to include the changes, or edit the affected duties.' : 'Start a new version to include them.'}</p>
          </div>
        </div>
      )}
      {s && (
        <dl className="grid gap-3 sm:grid-cols-4">
          <Stat label="Positions filled" value={`${s.filled} of ${s.positions}`} warn={s.filled < s.positions} />
          <Stat label="Faculty" value={String(s.faculty)} />
          <Stat label="Duties per person" value={`${s.minDuties}-${s.maxDuties} (avg ${s.meanDuties})`} />
          <Stat label="Spread (std. dev.)" value={String(s.stdDevDuties)} />
        </dl>
      )}
      {chart.unfilled.length > 0 && (
        <Section title={`Unfilled positions (${unfilledTotal})`} description="Nobody could take these without breaking a strict rule.">
          <ul className="space-y-4">
            {chart.unfilled.map((u) => (
              <li key={`${u.sessionHallId}-${u.role}`} className="rounded-md border border-red-200 bg-red-50 p-3 text-sm">
                <p className="flex items-center gap-2 font-medium text-red-900">
                  <AlertTriangle className="size-4" aria-hidden />
                  {formatDate(u.date)} {u.sessionLabel} · {u.hallCode}: {u.missing} {u.role === 'CHIEF' ? 'chief ' : ''}position{u.missing > 1 ? 's' : ''} open
                </p>
                <p className="mt-1 text-neutral-700">
                  Of {u.reason.totalFaculty} faculty:{' '}
                  {Object.entries(u.reason.eliminated).map(([r, n]) => `${n} ${REASON_LABEL[r] ?? r}`).join(', ') || 'none excluded'}.
                </p>
                <ul className="mt-1 list-disc pl-5 text-neutral-700">
                  {u.reason.suggestions.map((t) => (
                    <li key={t}>{t}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </Section>
      )}
      {editing && <ChartEditor key={JSON.stringify(editing)} chart={chart} target={editing} onDone={() => setEditing(null)} />}
      {notice && <SuccessNotice>{notice}</SuccessNotice>}
      {attendanceProblem && <ErrorAlert>{attendanceProblem}</ErrorAlert>}
      {substituting && (
        <SubstitutePanel
          key={substituting.duty.assignmentId}
          periodId={chart.periodId}
          target={substituting}
          onDone={(message) => {
            setSubstituting(null)
            setNotice(message)
          }}
        />
      )}
      {editable && <Publish chart={chart} />}
      <ChartGrid
        sessions={chart.sessions}
        unfilled={chart.unfilled}
        issueIds={new Set(chart.issues.map((i) => i.assignmentId))}
        onEdit={edit}
        onAttendance={chart.status === 'PUBLISHED' ? (duty, absent) => void markAttendance(duty, absent) : undefined}
        onSubstitute={
          substitutable
            ? (target) => {
                setNotice('')
                setSubstituting(target)
              }
            : undefined
        }
      />
      <Workload chart={chart} />
      {!readOnly && <History periodId={chart.periodId} />}
    </div>
  )
}

function Stat({ label, value, warn }: { label: string; value: string; warn?: boolean }) {
  return (
    <div className={`rounded-lg border p-3 ${warn ? 'border-red-200 bg-red-50' : 'border-neutral-200 bg-white'}`}>
      <dt className="text-xs text-neutral-500">{label}</dt>
      <dd className="text-lg font-semibold text-neutral-900">{value}</dd>
    </div>
  )
}

function ChartGrid({
  sessions,
  unfilled,
  issueIds,
  onEdit,
  onSubstitute,
  onAttendance,
}: {
  sessions: ChartSession[]
  unfilled: Unfilled[]
  issueIds: Set<string>
  onEdit?: (target: EditTarget) => void
  onSubstitute?: (target: SubstituteTarget) => void
  onAttendance?: (duty: Duty, absent: boolean) => void
}) {
  const dates = [...new Set(sessions.map((s) => s.date))]
  const [date, setDate] = useState(dates[0] ?? '')
  const shown = sessions.filter((s) => s.date === date && s.halls.length > 0)
  return (
    <Section
      title="Duty chart"
      description={
        onEdit
          ? 'Click a name to see why that person was chosen. Replace, remove or fill any position.'
          : onSubstitute
            ? 'Click a name to see why that person was chosen. If someone cannot do a duty, choose a substitute.'
            : 'Click a name to see why that person was chosen.'
      }
    >
      <div className="max-w-xs">
        <SelectField label="Date" value={date} onChange={(e) => setDate(e.target.value)} options={dates.map((d) => ({ value: d, label: formatDate(d) }))} />
      </div>
      {shown.length === 0 && <p className="text-sm text-neutral-500">No halls in use on this date.</p>}
      {shown.map((s) => (
        <div key={s.id}>
          <h3 className="mb-2 text-sm font-semibold text-neutral-800">
            {s.label} {hhmm(s.startTime)}-{hhmm(s.endTime)}
          </h3>
          <Table head={['Hall', 'Needed', 'Invigilators']}>
            {s.halls.map((h) => (
              <tr key={h.sessionHallId}>
                <td className="px-4 py-2 align-top font-medium text-neutral-900">{h.hallCode}</td>
                <td className="px-4 py-2 align-top text-neutral-600">{h.required}</td>
                <td className="px-4 py-2">
                  <ul className="space-y-1">
                    {h.duties.map((d) => (
                      <li key={d.assignmentId} className="flex items-start justify-between gap-3">
                        <details className="min-w-0">
                          <summary className={`cursor-pointer ${issueIds.has(d.assignmentId) ? 'text-red-700' : 'text-neutral-800'}`}>
                            {d.facultyName} <span className="text-neutral-500">({d.departmentCode})</span>
                            {d.role === 'CHIEF' && <span className="ml-2 rounded bg-primary-50 px-1.5 text-xs font-medium text-primary-700">chief</span>}
                            {d.source === 'MANUAL' && <span className="ml-2 rounded bg-neutral-100 px-1.5 text-xs font-medium text-neutral-600">edited</span>}
                            {d.source === 'SWAP' && <span className="ml-2 rounded bg-neutral-100 px-1.5 text-xs font-medium text-neutral-600">swapped</span>}
                            {d.source === 'SUBSTITUTE' && <span className="ml-2 rounded bg-amber-50 px-1.5 text-xs font-medium text-amber-800">substitute</span>}
                            {d.status === 'ABSENT' && <span className="ml-2 rounded bg-red-50 px-1.5 text-xs font-medium text-red-700">absent</span>}
                            {issueIds.has(d.assignmentId) && <span className="ml-2 text-xs font-medium">breaks a rule</span>}
                          </summary>
                          <p className="mt-1 text-xs text-neutral-600">{explain(d)}</p>
                        </details>
                        {onEdit && (
                          <span className="flex shrink-0 gap-2 text-xs">
                            <button
                              type="button"
                              className="font-medium link"
                              aria-label={`Replace ${d.facultyName} in ${h.hallCode}`}
                              onClick={() => onEdit({ kind: 'replace', session: s, hall: h, duty: d })}
                            >
                              Replace
                            </button>
                            <button
                              type="button"
                              className="font-medium text-neutral-600 hover:underline"
                              aria-label={`Remove ${d.facultyName} from ${h.hallCode}`}
                              onClick={() => onEdit({ kind: 'remove', session: s, hall: h, duty: d })}
                            >
                              Remove
                            </button>
                          </span>
                        )}
                        {onAttendance && sessionEnded(s) && (
                          <button
                            type="button"
                            className="shrink-0 text-xs font-medium text-neutral-600 hover:underline"
                            aria-label={`${d.status === 'ABSENT' ? 'Mark present' : 'Mark absent'}: ${d.facultyName} in ${h.hallCode}`}
                            onClick={() => onAttendance(d, d.status !== 'ABSENT')}
                          >
                            {d.status === 'ABSENT' ? 'Mark present' : 'Mark absent'}
                          </button>
                        )}
                        {onSubstitute && !sessionEnded(s) && (
                          <button
                            type="button"
                            className="shrink-0 text-xs font-medium link"
                            aria-label={`Substitute for ${d.facultyName} in ${h.hallCode}`}
                            onClick={() => onSubstitute({ session: s, hall: h, duty: d })}
                          >
                            Substitute
                          </button>
                        )}
                      </li>
                    ))}
                    {unfilled
                      .filter((u) => u.sessionHallId === h.sessionHallId)
                      .map((u) => (
                        <li key={u.role} className="flex items-center justify-between gap-3 text-sm font-medium text-red-700">
                          <span>
                            {u.missing} {u.role === 'CHIEF' ? 'chief ' : ''}open
                          </span>
                          {onEdit && (
                            <button
                              type="button"
                              className="text-xs font-medium link"
                              aria-label={`Fill ${u.role === 'CHIEF' ? 'chief ' : ''}position in ${h.hallCode}`}
                              onClick={() => onEdit({ kind: 'fill', session: s, hall: h, role: u.role })}
                            >
                              Fill
                            </button>
                          )}
                        </li>
                      ))}
                  </ul>
                </td>
              </tr>
            ))}
          </Table>
        </div>
      ))}
    </Section>
  )
}

/** The pre-publish checklist and the publish button. */
function Publish({ chart }: { chart: DutyChart }) {
  const publish = usePublishChart(chart.periodId)
  const [problem, setProblem] = useState<string | null>(null)
  const open = chart.unfilled.reduce((n, u) => n + u.missing, 0)
  const blocked = chart.issues.length > 0

  async function run() {
    const lines = [`Publish version ${chart.chartVersion}? Faculty will see these duties and the chart can no longer be edited.`]
    if (chart.publishedVersion != null) lines.push(`It replaces published version ${chart.publishedVersion}.`)
    if (open > 0) lines.push(`${open} position${open === 1 ? ' is' : 's are'} still open.`)
    if (chart.changesNotInDraft > 0)
      lines.push(`${chart.changesNotInDraft} swap${chart.changesNotInDraft === 1 ? ' or substitute' : 's or substitutes'} made since this draft was started will be undone.`)
    if (!window.confirm(lines.join('\n\n'))) return
    setProblem(null)
    try {
      await publish.mutateAsync(chart.version)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not publish the chart.')
    }
  }

  return (
    <Section title="Review and publish" description="Publishing freezes this version and makes it the chart faculty see.">
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      <ul className="space-y-1 text-sm">
        <Check ok={!blocked} warn={false}>
          {blocked
            ? `${chart.issues.length} dut${chart.issues.length === 1 ? 'y breaks' : 'ies break'} a strict rule; fix ${chart.issues.length === 1 ? 'it' : 'them'} before publishing.`
            : 'No duty breaks a strict rule.'}
        </Check>
        <Check ok={open === 0} warn>
          {open === 0 ? 'Every position is filled.' : `${open} position${open === 1 ? ' is' : 's are'} still open. You can publish anyway.`}
        </Check>
        {chart.changesNotInDraft > 0 && (
          <Check ok={false} warn>
            {chart.changesNotInDraft === 1 ? '1 swap or substitute was' : `${chart.changesNotInDraft} swaps or substitutes were`} made on
            published version {chart.publishedVersion} after this draft was started. This draft does not have{' '}
            {chart.changesNotInDraft === 1 ? 'it' : 'them'}: publishing it undoes {chart.changesNotInDraft === 1 ? 'that change' : 'those changes'}.
          </Check>
        )}
        {(chart.inputsChanged || chart.policyChanged) && (
          <Check ok={false} warn>
            Inputs or the policy changed after generation; check the duties are still what you want.
          </Check>
        )}
      </ul>
      <div className="mt-4">
        <Button loading={publish.isPending} disabled={blocked} onClick={() => void run()}>
          Publish version {chart.chartVersion}
        </Button>
      </div>
    </Section>
  )
}

function Check({ ok, warn, children }: { ok: boolean; warn: boolean; children: ReactNode }) {
  const tone = ok ? 'text-emerald-700' : warn ? 'text-amber-800' : 'text-red-700'
  const Icon = ok ? CheckCircle2 : AlertTriangle
  return (
    <li className={`flex items-start gap-2 ${tone}`}>
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <span>{children}</span>
    </li>
  )
}

function Versions({ periodId }: { periodId: string }) {
  const versions = useChartVersions(periodId)
  return (
    <Section title="Versions" description="Every version of this chart. A published version changes only through approved swaps; a newer published version replaces the older one.">
      {versions.error && <ErrorAlert>{toApiProblem(versions.error).detail ?? 'Could not load the versions.'}</ErrorAlert>}
      {versions.data && (
        <Table head={['Version', 'Status', 'Filled', 'Created', 'Published', '']}>
          {versions.data.map((v) => (
            <tr key={v.chartVersion}>
              <td className="px-4 py-2 font-medium text-neutral-900">{v.chartVersion}</td>
              <td className="px-4 py-2 text-neutral-700">{STATUS_LABEL[v.status]}</td>
              <td className="px-4 py-2 text-neutral-600">
                {v.filled} of {v.positions}
              </td>
              <td className="px-4 py-2 text-neutral-600">{formatDateTime(v.createdAt)}</td>
              <td className="px-4 py-2 text-neutral-600">
                {v.publishedAt ? `${formatDateTime(v.publishedAt)}${v.publishedBy ? ` · ${v.publishedBy}` : ''}` : '-'}
              </td>
              <td className="px-4 py-2">
                <Link to={`/admin/exams/${periodId}/allocation/versions/${v.chartVersion}`} className="text-sm font-medium link">
                  View
                </Link>
              </td>
            </tr>
          ))}
        </Table>
      )}
    </Section>
  )
}

function History({ periodId }: { periodId: string }) {
  const history = useChartHistory(periodId)
  return (
    <Section title="Edit history" description="Generation and every manual change to this chart, newest first.">
      {history.isPending && <p className="text-sm text-neutral-500">Loading history...</p>}
      {history.error && <ErrorAlert>{toApiProblem(history.error).detail ?? 'Could not load the history.'}</ErrorAlert>}
      {history.data?.length === 0 && <p className="text-sm text-neutral-500">No changes yet.</p>}
      {history.data && history.data.length > 0 && (
        <ol className="space-y-2">
          {history.data.map((h) => (
            <li key={h.id} className="text-sm">
              <p className="text-neutral-800">{h.summary}</p>
              <p className="text-xs text-neutral-500">
                {formatDateTime(h.occurredAt)}
                {h.actorName ? ` · ${h.actorName}` : ''}
                {h.reason ? ` · Reason: ${h.reason}` : ''}
              </p>
            </li>
          ))}
        </ol>
      )}
    </Section>
  )
}

function Workload({ chart }: { chart: DutyChart }) {
  return (
    <Section title="Workload" description="Duties per faculty member in this draft. Duty weight below 1 means a reduced share.">
      <Table head={['Faculty', 'Department', 'Duties', 'As chief', 'Weight']}>
        {chart.workload.map((w) => (
          <tr key={w.facultyId}>
            <td className="px-4 py-2 text-neutral-800">
              {w.name} <span className="text-neutral-500">{w.code}</span>
            </td>
            <td className="px-4 py-2 text-neutral-600">{w.departmentCode}</td>
            <td className={`px-4 py-2 font-medium ${w.duties === 0 ? 'text-neutral-400' : 'text-neutral-900'}`}>{w.duties}</td>
            <td className="px-4 py-2 text-neutral-600">{w.chiefDuties}</td>
            <td className="px-4 py-2 text-neutral-600">{Number(w.dutyWeight).toFixed(2)}</td>
          </tr>
        ))}
      </Table>
    </Section>
  )
}

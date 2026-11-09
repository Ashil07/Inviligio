import { AlertTriangle, Info } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button, ErrorAlert } from '@/components/ui/form'
import { Section, SelectField } from '@/components/ui/fields'
import { PageHeader, Table } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { formatDate, hhmm, usePeriod } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import { formatDateTime } from '@/lib/forms'
import {
  REASON_LABEL,
  explain,
  useChartHistory,
  useDutyChart,
  useGenerateChart,
  type ChartSession,
  type DutyChart,
  type Unfilled,
} from './api'
import { ChartEditor, type EditTarget } from './ChartEditor'

/** Generate the period's draft duty chart and review it. */
export function AllocationPage() {
  const periodId = useParams().periodId!
  const period = usePeriod(periodId)
  const chart = useDutyChart(periodId)
  const generate = useGenerateChart(periodId)
  const [problem, setProblem] = useState<string | null>(null)

  if (period.error) return <ErrorAlert>{toApiProblem(period.error).detail ?? 'Could not load the period.'}</ErrorAlert>
  if (!period.data || chart.isPending) return <FullPageSpinner label="Loading duty chart" />

  async function run() {
    if (chart.data && !window.confirm('Generate again? The current draft, including its explanations, will be replaced.')) return
    setProblem(null)
    try {
      await generate.mutateAsync()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Allocation failed.')
    }
  }

  const c = chart.data
  return (
    <div className="space-y-6">
      <Link to={`/admin/exams/${periodId}`} className="text-sm text-indigo-700 hover:underline">← {period.data.name}</Link>
      <PageHeader
        title="Duty allocation"
        description="Invigilo assigns faculty to every hall using the college policy. Strict rules are never broken; positions it cannot fill are listed with the reasons."
        actions={<Button loading={generate.isPending} onClick={() => void run()}>{c ? 'Generate again' : 'Generate duty chart'}</Button>}
      />
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {chart.error && <ErrorAlert>{toApiProblem(chart.error).detail ?? 'Could not load the chart.'}</ErrorAlert>}
      {!c && !chart.error && (
        <p className="text-sm text-slate-600">No duty chart yet. Add sessions, exams and seating, then generate.</p>
      )}
      {c && <ChartReview chart={c} />}
    </div>
  )
}

function ChartReview({ chart }: { chart: DutyChart }) {
  const s = chart.stats
  const unfilledTotal = chart.unfilled.reduce((n, u) => n + u.missing, 0)
  const editable = chart.status === 'DRAFT'
  const [editing, setEditing] = useState<EditTarget | null>(null)
  const edit = editable ? setEditing : undefined

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
      <p className="text-sm text-slate-600">
        {editable ? 'Draft' : 'Version'} {chart.chartVersion}, generated {formatDateTime(chart.generatedAt)}
        {chart.policyVersion != null ? ` with policy version ${chart.policyVersion}` : ''}.
        {editable && ' You can change any duty below; every change is checked against the same rules as generation.'}
      </p>
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
            <p>Generate again to include the changes.</p>
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
                <p className="mt-1 text-slate-700">
                  Of {u.reason.totalFaculty} faculty:{' '}
                  {Object.entries(u.reason.eliminated).map(([r, n]) => `${n} ${REASON_LABEL[r] ?? r}`).join(', ') || 'none excluded'}.
                </p>
                <ul className="mt-1 list-disc pl-5 text-slate-700">
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
      <ChartGrid sessions={chart.sessions} unfilled={chart.unfilled} issueIds={new Set(chart.issues.map((i) => i.assignmentId))} onEdit={edit} />
      <Workload chart={chart} />
      <History periodId={chart.periodId} />
    </div>
  )
}

function Stat({ label, value, warn }: { label: string; value: string; warn?: boolean }) {
  return (
    <div className={`rounded-lg border p-3 ${warn ? 'border-red-200 bg-red-50' : 'border-slate-200 bg-white'}`}>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="text-lg font-semibold text-slate-900">{value}</dd>
    </div>
  )
}

function ChartGrid({
  sessions,
  unfilled,
  issueIds,
  onEdit,
}: {
  sessions: ChartSession[]
  unfilled: Unfilled[]
  issueIds: Set<string>
  onEdit?: (target: EditTarget) => void
}) {
  const dates = [...new Set(sessions.map((s) => s.date))]
  const [date, setDate] = useState(dates[0] ?? '')
  const shown = sessions.filter((s) => s.date === date && s.halls.length > 0)
  return (
    <Section
      title="Duty chart"
      description={onEdit ? 'Click a name to see why that person was chosen. Replace, remove or fill any position.' : 'Click a name to see why that person was chosen.'}
    >
      <div className="max-w-xs">
        <SelectField label="Date" value={date} onChange={(e) => setDate(e.target.value)} options={dates.map((d) => ({ value: d, label: formatDate(d) }))} />
      </div>
      {shown.length === 0 && <p className="text-sm text-slate-500">No halls in use on this date.</p>}
      {shown.map((s) => (
        <div key={s.id}>
          <h3 className="mb-2 text-sm font-semibold text-slate-800">
            {s.label} {hhmm(s.startTime)}-{hhmm(s.endTime)}
          </h3>
          <Table head={['Hall', 'Needed', 'Invigilators']}>
            {s.halls.map((h) => (
              <tr key={h.sessionHallId}>
                <td className="px-4 py-2 align-top font-medium text-slate-900">{h.hallCode}</td>
                <td className="px-4 py-2 align-top text-slate-600">{h.required}</td>
                <td className="px-4 py-2">
                  <ul className="space-y-1">
                    {h.duties.map((d) => (
                      <li key={d.assignmentId} className="flex items-start justify-between gap-3">
                        <details className="min-w-0">
                          <summary className={`cursor-pointer ${issueIds.has(d.assignmentId) ? 'text-red-700' : 'text-slate-800'}`}>
                            {d.facultyName} <span className="text-slate-500">({d.departmentCode})</span>
                            {d.role === 'CHIEF' && <span className="ml-2 rounded bg-indigo-50 px-1.5 text-xs font-medium text-indigo-700">chief</span>}
                            {d.source === 'MANUAL' && <span className="ml-2 rounded bg-slate-100 px-1.5 text-xs font-medium text-slate-600">edited</span>}
                            {issueIds.has(d.assignmentId) && <span className="ml-2 text-xs font-medium">breaks a rule</span>}
                          </summary>
                          <p className="mt-1 text-xs text-slate-600">{explain(d)}</p>
                        </details>
                        {onEdit && (
                          <span className="flex shrink-0 gap-2 text-xs">
                            <button
                              type="button"
                              className="font-medium text-indigo-700 hover:underline"
                              aria-label={`Replace ${d.facultyName} in ${h.hallCode}`}
                              onClick={() => onEdit({ kind: 'replace', session: s, hall: h, duty: d })}
                            >
                              Replace
                            </button>
                            <button
                              type="button"
                              className="font-medium text-slate-600 hover:underline"
                              aria-label={`Remove ${d.facultyName} from ${h.hallCode}`}
                              onClick={() => onEdit({ kind: 'remove', session: s, hall: h, duty: d })}
                            >
                              Remove
                            </button>
                          </span>
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
                              className="text-xs font-medium text-indigo-700 hover:underline"
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

function History({ periodId }: { periodId: string }) {
  const history = useChartHistory(periodId)
  return (
    <Section title="Edit history" description="Generation and every manual change to this chart, newest first.">
      {history.isPending && <p className="text-sm text-slate-500">Loading history...</p>}
      {history.error && <ErrorAlert>{toApiProblem(history.error).detail ?? 'Could not load the history.'}</ErrorAlert>}
      {history.data?.length === 0 && <p className="text-sm text-slate-500">No changes yet.</p>}
      {history.data && history.data.length > 0 && (
        <ol className="space-y-2">
          {history.data.map((h) => (
            <li key={h.id} className="text-sm">
              <p className="text-slate-800">{h.summary}</p>
              <p className="text-xs text-slate-500">
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
            <td className="px-4 py-2 text-slate-800">
              {w.name} <span className="text-slate-500">{w.code}</span>
            </td>
            <td className="px-4 py-2 text-slate-600">{w.departmentCode}</td>
            <td className={`px-4 py-2 font-medium ${w.duties === 0 ? 'text-slate-400' : 'text-slate-900'}`}>{w.duties}</td>
            <td className="px-4 py-2 text-slate-600">{w.chiefDuties}</td>
            <td className="px-4 py-2 text-slate-600">{Number(w.dutyWeight).toFixed(2)}</td>
          </tr>
        ))}
      </Table>
    </Section>
  )
}

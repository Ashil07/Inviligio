import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { useState } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { ErrorAlert } from '@/components/ui/form'
import { Section, SelectField } from '@/components/ui/fields'
import { PageHeader, Table } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { REASON_LABEL } from '@/features/allocation/api'
import { formatDate } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import { distribution, useFairness, type FacultyRow, type Load } from './api'

// Chart roles (validated categorical slots 1 and 2; recessive chrome; text never wears a series colour).
const SERIES_1 = '#2a78d6'
const SERIES_2 = '#eb6834'
const GRID = '#e1e0d9'
const AXIS = '#c3c2b7'
const MUTED = '#898781'
const INK = '#52514e'
const tick = { fill: MUTED, fontSize: 12 }

const LOAD_STYLE: Record<Load, { label: string; className: string }> = {
  OVER: { label: 'Over', className: 'bg-red-50 text-red-700' },
  UNDER: { label: 'Under', className: 'bg-amber-100 text-amber-800' },
  BALANCED: { label: 'Balanced', className: 'bg-emerald-50 text-emerald-700' },
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/** Exam cell: how evenly duties are spread, from published charts only. */
export function FairnessPage() {
  const [periodId, setPeriodId] = useState<string>()
  const fairness = useFairness(periodId)

  if (fairness.isPending) return <FullPageSpinner label="Loading fairness figures" />
  if (fairness.error) {
    return (
      <div>
        <ErrorAlert>{toApiProblem(fairness.error).detail ?? 'Could not load the fairness figures.'}</ErrorAlert>
      </div>
    )
  }
  const data = fairness.data
  const s = data.summary
  return (
    <div className="space-y-6">
      <PageHeader
        title="Fairness"
        description="How evenly invigilation duties are spread, from published duty charts (swaps and substitutes included). Per-person figures count every active faculty member, including those with no duty."
        actions={
          data.periods.length > 0 ? (
            <div className="w-64">
              <SelectField
                label="Exam period"
                value={data.periodId ?? ''}
                onChange={(e) => setPeriodId(e.target.value)}
                options={data.periods.map((p) => ({ value: p.id, label: `${p.name} (v${p.publishedVersion})` }))}
              />
            </div>
          ) : undefined
        }
      />
      {!s ? (
        <p className="rounded-md border border-neutral-200 bg-white p-4 text-sm text-neutral-600">
          No duty chart has been published yet. The figures appear once the first chart is published.
        </p>
      ) : (
        <>
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Stat label="Positions filled" value={`${s.filled} of ${s.positions}`} warn={s.unfilled > 0} />
            <Stat label="Average duties" value={`${s.mean}`} hint={`per person, range ${s.min}-${s.max}`} />
            <Stat label="Spread (std. dev.)" value={`${s.stdDev}`} hint="lower is more even" />
            <Stat label="Without a duty" value={`${s.withoutDuty} of ${s.activeFaculty}`} />
          </dl>
          <Verdict over={s.over} under={s.under} />
          <Distribution rows={data.faculty} mean={s.mean} />
          <Departments data={data} />
          <FacultyTable rows={data.faculty} departments={data.departments.map((d) => d.code)} />
          {data.unfilled.length > 0 && <Unfilled data={data} />}
        </>
      )}
      {data.history.length > 0 && <History data={data} />}
    </div>
  )
}

function Stat({ label, value, hint, warn }: { label: string; value: string; hint?: string; warn?: boolean }) {
  return (
    <div className={`rounded-lg border p-3 ${warn ? 'border-red-200 bg-red-50' : 'border-neutral-200 bg-white'}`}>
      <dt className="text-xs text-neutral-500">{label}</dt>
      <dd className="text-lg font-semibold text-neutral-900">{value}</dd>
      {hint && <dd className="text-xs text-neutral-500">{hint}</dd>}
    </div>
  )
}

function Verdict({ over, under }: { over: number; under: number }) {
  if (over === 0 && under === 0) {
    return (
      <p className="flex items-center gap-2 text-sm text-emerald-700">
        <CheckCircle2 className="size-4" aria-hidden /> Everyone is within one duty of an even share for their duty weight.
      </p>
    )
  }
  return (
    <p className="flex items-center gap-2 text-sm text-amber-800">
      <AlertTriangle className="size-4" aria-hidden />
      {[over > 0 && `${plural(over, 'person')} over`, under > 0 && `${plural(under, 'person')} under`].filter(Boolean).join(' and ')} an even
      share by more than one duty.
    </p>
  )
}

function Distribution({ rows, mean }: { rows: FacultyRow[]; mean: number }) {
  const bins = distribution(rows)
  return (
    <Section title="Duties per person" description="How many active faculty members have each number of duties. The line marks the average.">
      <figure aria-label="Faculty by number of duties" className="h-64 rounded-lg border border-neutral-200 bg-white p-3">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={bins} margin={{ top: 16, right: 16, bottom: 8, left: 0 }}>
            <CartesianGrid vertical={false} stroke={GRID} />
            <XAxis
              type="number"
              dataKey="duties"
              domain={[-0.5, bins.length - 0.5]}
              ticks={bins.map((b) => b.duties)}
              allowDecimals={false}
              tick={tick}
              stroke={AXIS}
              tickLine={false}
              label={{ value: 'duties', position: 'insideBottomRight', offset: -4, fill: MUTED, fontSize: 12 }}
            />
            <YAxis allowDecimals={false} tick={tick} stroke={AXIS} tickLine={false} axisLine={false} width={32} />
            <Tooltip
              cursor={{ fill: 'rgba(11,11,11,0.04)' }}
              formatter={(value) => [plural(Number(value), 'person'), 'Faculty']}
              labelFormatter={(label) => plural(Number(label), 'duty')}
            />
            <ReferenceLine x={mean} stroke={MUTED} label={{ value: `average ${mean}`, position: 'top', fill: INK, fontSize: 12 }} />
            <Bar dataKey="faculty" name="Faculty" fill={SERIES_1} maxBarSize={24} radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </figure>
    </Section>
  )
}

function Departments({ data }: { data: NonNullable<ReturnType<typeof useFairness>['data']> }) {
  const mean = data.summary?.mean ?? 0
  return (
    <Section title="Departments" description="Duties per active faculty member in each department, against the college average.">
      <figure aria-label="Duties per person by department" className="rounded-lg border border-neutral-200 bg-white p-3" style={{ height: Math.max(160, data.departments.length * 36 + 48) }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data.departments} layout="vertical" margin={{ top: 16, right: 24, bottom: 8, left: 8 }}>
            <CartesianGrid horizontal={false} stroke={GRID} />
            <XAxis type="number" tick={tick} stroke={AXIS} tickLine={false} />
            <YAxis type="category" dataKey="code" tick={tick} stroke={AXIS} tickLine={false} width={56} />
            <Tooltip cursor={{ fill: 'rgba(11,11,11,0.04)' }} formatter={(value) => [String(value), 'Duties per person']} />
            <ReferenceLine x={mean} stroke={MUTED} label={{ value: `college ${mean}`, position: 'top', fill: INK, fontSize: 12 }} />
            <Bar dataKey="perPerson" name="Duties per person" fill={SERIES_1} maxBarSize={24} radius={[0, 4, 4, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </figure>
      <div className="mt-3">
        <Table head={['Department', 'Faculty', 'Duties', 'Per person', 'Range', 'Over', 'Under']}>
          {data.departments.map((d) => (
            <tr key={d.code}>
              <td className="px-4 py-2 text-neutral-800">
                {d.name} <span className="text-neutral-500">({d.code})</span>
              </td>
              <td className="px-4 py-2 text-neutral-600">{d.faculty}</td>
              <td className="px-4 py-2 text-neutral-600">{d.duties}</td>
              <td className="px-4 py-2 font-medium text-neutral-900">{d.perPerson}</td>
              <td className="px-4 py-2 text-neutral-600">
                {d.min}-{d.max}
              </td>
              <td className="px-4 py-2 text-neutral-600">{d.over}</td>
              <td className="px-4 py-2 text-neutral-600">{d.under}</td>
            </tr>
          ))}
        </Table>
      </div>
    </Section>
  )
}

function FacultyTable({ rows, departments }: { rows: FacultyRow[]; departments: string[] }) {
  const [department, setDepartment] = useState('')
  const [onlyUneven, setOnlyUneven] = useState(false)
  const shown = rows.filter((r) => (!department || r.departmentCode === department) && (!onlyUneven || r.load === 'OVER' || r.load === 'UNDER'))
  return (
    <Section title="Faculty" description="Each person's duties in this period against an even share for their duty weight, and across every published period.">
      <div className="flex flex-wrap items-end gap-4">
        <div className="w-56">
          <SelectField
            label="Department"
            value={department}
            onChange={(e) => setDepartment(e.target.value)}
            options={[{ value: '', label: 'All departments' }, ...departments.map((d) => ({ value: d, label: d }))]}
          />
        </div>
        <label className="flex items-center gap-2 pb-2 text-sm text-neutral-700">
          <input type="checkbox" checked={onlyUneven} onChange={(e) => setOnlyUneven(e.target.checked)} />
          Only over or under
        </label>
      </div>
      <div className="mt-3">
        <Table head={['Faculty', 'Department', 'Duties', 'As chief', 'Even share', 'Workload', 'All periods']} empty={shown.length === 0 && 'Nobody matches.'}>
          {shown.map((f) => (
            <tr key={f.facultyId}>
              <td className="px-4 py-2 text-neutral-800">
                {f.name} <span className="text-neutral-500">({f.code})</span>
              </td>
              <td className="px-4 py-2 text-neutral-600">{f.departmentCode}</td>
              <td className="px-4 py-2 font-medium text-neutral-900">{f.duties}</td>
              <td className="px-4 py-2 text-neutral-600">{f.chiefDuties}</td>
              <td className="px-4 py-2 text-neutral-600">{f.active ? f.fairShare : '-'}</td>
              <td className="px-4 py-2">
                {f.load ? (
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${LOAD_STYLE[f.load].className}`}>{LOAD_STYLE[f.load].label}</span>
                ) : (
                  <span className="rounded-full bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-600">Inactive</span>
                )}
              </td>
              <td className="px-4 py-2 text-neutral-600">{f.allPeriods}</td>
            </tr>
          ))}
        </Table>
      </div>
    </Section>
  )
}

function Unfilled({ data }: { data: NonNullable<ReturnType<typeof useFairness>['data']> }) {
  return (
    <Section title="Unfilled positions" description="Positions nobody could take without breaking a strict rule, and why.">
      <Table head={['Date', 'Session', 'Hall', 'Open', 'Why nobody could take it']}>
        {data.unfilled.map((u) => (
          <tr key={`${u.date}-${u.sessionLabel}-${u.hallCode}-${u.role}`}>
            <td className="px-4 py-2 text-neutral-800">{formatDate(u.date)}</td>
            <td className="px-4 py-2 text-neutral-600">{u.sessionLabel}</td>
            <td className="px-4 py-2 text-neutral-800">{u.hallCode}</td>
            <td className="px-4 py-2 text-neutral-600">
              {u.missing} {u.role === 'CHIEF' ? 'chief' : 'invigilator'}
            </td>
            <td className="px-4 py-2 text-neutral-600">
              {Object.entries(u.eliminated).map(([r, n]) => `${n} ${REASON_LABEL[r] ?? r}`).join(', ') || '-'}
            </td>
          </tr>
        ))}
      </Table>
    </Section>
  )
}

function History({ data }: { data: NonNullable<ReturnType<typeof useFairness>['data']> }) {
  return (
    <Section title="Across exam periods" description="Average duties per person and their spread in every published period, oldest first.">
      {data.history.length > 1 && (
        <figure aria-label="Average and spread of duties by exam period" className="h-64 rounded-lg border border-neutral-200 bg-white p-3">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data.history} margin={{ top: 16, right: 24, bottom: 8, left: 0 }}>
              <CartesianGrid vertical={false} stroke={GRID} />
              <XAxis dataKey="name" tick={tick} stroke={AXIS} tickLine={false} />
              <YAxis tick={tick} stroke={AXIS} tickLine={false} axisLine={false} width={32} />
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 12, color: INK }} />
              <Line type="monotone" dataKey="mean" name="Duties per person" stroke={SERIES_1} strokeWidth={2} dot={{ r: 4, strokeWidth: 2, stroke: '#ffffff' }} />
              <Line type="monotone" dataKey="stdDev" name="Spread (std. dev.)" stroke={SERIES_2} strokeWidth={2} dot={{ r: 4, strokeWidth: 2, stroke: '#ffffff' }} />
            </LineChart>
          </ResponsiveContainer>
        </figure>
      )}
      <div className="mt-3">
        <Table head={['Exam period', 'Starts', 'Filled', 'Per person', 'Range', 'Spread']}>
          {data.history.map((h) => (
            <tr key={h.periodId}>
              <td className="px-4 py-2 text-neutral-800">{h.name}</td>
              <td className="px-4 py-2 text-neutral-600">{formatDate(h.startDate)}</td>
              <td className="px-4 py-2 text-neutral-600">
                {h.filled} of {h.positions}
              </td>
              <td className="px-4 py-2 font-medium text-neutral-900">{h.mean}</td>
              <td className="px-4 py-2 text-neutral-600">
                {h.min}-{h.max}
              </td>
              <td className="px-4 py-2 text-neutral-600">{h.stdDev}</td>
            </tr>
          ))}
        </Table>
      </div>
    </Section>
  )
}

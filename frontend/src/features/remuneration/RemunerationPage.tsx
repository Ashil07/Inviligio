import { zodResolver } from '@hookform/resolvers/zod'
import { AlertTriangle, Download } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { Section, SelectField, SuccessNotice } from '@/components/ui/fields'
import { PageHeader, Table } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useDownloadReport, type ReportFormat } from '@/features/reports/api'
import { formatDate } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import { money, useAddRate, useDeleteRate, usePay, useRates, type Pay, type Rate } from './api'

const roleName = (role: Rate['role']) => (role === 'CHIEF' ? 'Chief invigilator' : 'Invigilator')

/** Exam cell: pay rates, and what each faculty member earns from the duties they did. */
export function RemunerationPage() {
  const [periodId, setPeriodId] = useState<string>()
  const pay = usePay(periodId)
  return (
    <div className="space-y-6">
      <PageHeader
        title="Remuneration"
        description="Pay for invigilation duties. A duty is paid once its session has ended, at the rate for its role on that day, unless the person was marked absent on the duty chart."
      />
      <Rates />
      {pay.isPending ? (
        <FullPageSpinner label="Loading pay" />
      ) : pay.error ? (
        <ErrorAlert>{toApiProblem(pay.error).detail ?? 'Could not load pay.'}</ErrorAlert>
      ) : (
        <PeriodPay pay={pay.data} onPeriod={setPeriodId} />
      )}
    </div>
  )
}

// ---- rates ----

const rateSchema = z.object({
  role: z.enum(['INVIGILATOR', 'CHIEF']),
  amount: z
    .string()
    .trim()
    .regex(/^\d{1,7}(\.\d{1,2})?$/, 'Enter an amount such as 500 or 750.50'),
  effectiveFrom: z.string().min(1, 'Choose the date the rate starts'),
})
type RateValues = z.infer<typeof rateSchema>

function Rates() {
  const rates = useRates()
  const add = useAddRate()
  const remove = useDeleteRate()
  const [problem, setProblem] = useState<string | null>(null)
  const form = useForm<RateValues>({ resolver: zodResolver(rateSchema), defaultValues: { role: 'INVIGILATOR', amount: '', effectiveFrom: '' } })
  const currency = rates.data?.[0]?.currency ?? 'INR'

  async function onSubmit(values: RateValues) {
    setProblem(null)
    try {
      await add.mutateAsync(values)
      form.reset({ role: values.role, amount: '', effectiveFrom: '' })
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not add the rate.')
    }
  }

  async function onDelete(rate: Rate) {
    if (!window.confirm(`Delete the ${roleName(rate.role).toLowerCase()} rate from ${formatDate(rate.effectiveFrom)}? Pay is recalculated.`)) return
    setProblem(null)
    try {
      await remove.mutateAsync(rate)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not delete the rate.')
    }
  }

  return (
    <Section title="Rates" description={`Pay per duty in ${currency}. A rate applies from its date until a newer rate for the same role starts, so past periods keep the rates of their time.`}>
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      <Table head={['Role', 'From', 'Amount', 'Added by', '']} empty={!rates.isPending && (rates.data ?? []).length === 0 && 'No rates yet. Add one below; until then duties are counted but not paid.'}>
        {(rates.data ?? []).map((r) => (
          <tr key={r.id}>
            <td className="px-4 py-2 text-neutral-800">{roleName(r.role)}</td>
            <td className="px-4 py-2 text-neutral-600">{formatDate(r.effectiveFrom)}</td>
            <td className="px-4 py-2 font-medium text-neutral-900">{money(r.amount, r.currency)}</td>
            <td className="px-4 py-2 text-neutral-600">{r.createdByName ?? '-'}</td>
            <td className="px-4 py-2 text-right">
              <button type="button" className="text-sm font-medium text-red-700 hover:underline" aria-label={`Delete ${roleName(r.role)} rate from ${r.effectiveFrom}`} onClick={() => void onDelete(r)}>
                Delete
              </button>
            </td>
          </tr>
        ))}
      </Table>
      <form className="mt-4 grid items-end gap-4 sm:grid-cols-4" onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <SelectField label="Role" options={[{ value: 'INVIGILATOR', label: 'Invigilator' }, { value: 'CHIEF', label: 'Chief invigilator' }]} {...form.register('role')} />
        <TextField label={`Amount (${currency})`} inputMode="decimal" error={form.formState.errors.amount?.message} {...form.register('amount')} />
        <TextField label="From" type="date" error={form.formState.errors.effectiveFrom?.message} {...form.register('effectiveFrom')} />
        <div>
          <Button type="submit" loading={add.isPending}>
            Add rate
          </Button>
        </div>
      </form>
    </Section>
  )
}

// ---- pay ----

function PeriodPay({ pay, onPeriod }: { pay: Pay; onPeriod: (id: string) => void }) {
  const s = pay.summary
  if (!s) {
    return <p className="rounded-md border border-neutral-200 bg-white p-4 text-sm text-neutral-600">No duty chart has been published yet, so there is nothing to pay.</p>
  }
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        {pay.periods.length > 1 ? (
          <div className="w-64">
            <SelectField
              label="Exam period"
              value={pay.periodId ?? ''}
              onChange={(e) => onPeriod(e.target.value)}
              options={pay.periods.map((p) => ({ value: p.id, label: `${p.name} (v${p.publishedVersion})` }))}
            />
          </div>
        ) : (
          <p className="text-sm text-neutral-600">
            {pay.periodName}: published duty chart version {pay.chartVersion}.
          </p>
        )}
        <Downloads periodId={pay.periodId!} />
      </div>
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Stat label="Total to pay" value={money(s.total, s.currency)} />
        <Stat label="Duties done" value={String(s.completed)} />
        <Stat label="Absent (not paid)" value={String(s.absent)} />
        <Stat label="Still to come" value={String(s.upcoming)} />
      </dl>
      {s.withoutRate > 0 && (
        <p role="status" className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden />
          {s.withoutRate === 1 ? '1 duty has' : `${s.withoutRate} duties have`} no rate for its date, so {s.withoutRate === 1 ? 'it is' : 'they are'} not in the
          total. Add a rate that starts on or before the exam dates.
        </p>
      )}
      <Section title="Faculty">
        <Table head={['Faculty', 'Department', 'Done', 'As chief', 'Absent', 'To come', 'Amount']} empty={pay.faculty.length === 0 && 'Nobody has duties in this chart.'}>
          {pay.faculty.map((f) => (
            <tr key={f.facultyId}>
              <td className="px-4 py-2 text-neutral-800">
                {f.name} <span className="text-neutral-500">({f.code})</span>
              </td>
              <td className="px-4 py-2 text-neutral-600">{f.departmentCode}</td>
              <td className="px-4 py-2 text-neutral-600">{f.completed}</td>
              <td className="px-4 py-2 text-neutral-600">{f.chiefCompleted}</td>
              <td className="px-4 py-2 text-neutral-600">{f.absent}</td>
              <td className="px-4 py-2 text-neutral-600">{f.upcoming}</td>
              <td className="px-4 py-2 font-medium text-neutral-900">
                {money(f.amount, s.currency)}
                {f.withoutRate > 0 && <span className="ml-1 text-xs font-normal text-amber-800">+{f.withoutRate} without rate</span>}
              </td>
            </tr>
          ))}
        </Table>
      </Section>
      <Section title="Departments">
        <Table head={['Department', 'Faculty', 'Duties done', 'Amount']}>
          {pay.departments.map((d) => (
            <tr key={d.code}>
              <td className="px-4 py-2 text-neutral-800">
                {d.name} <span className="text-neutral-500">({d.code})</span>
              </td>
              <td className="px-4 py-2 text-neutral-600">{d.faculty}</td>
              <td className="px-4 py-2 text-neutral-600">{d.completed}</td>
              <td className="px-4 py-2 font-medium text-neutral-900">{money(d.amount, s.currency)}</td>
            </tr>
          ))}
        </Table>
      </Section>
    </>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-3">
      <dt className="text-xs text-neutral-500">{label}</dt>
      <dd className="text-lg font-semibold text-neutral-900">{value}</dd>
    </div>
  )
}

function Downloads({ periodId }: { periodId: string }) {
  const download = useDownloadReport()
  const [saved, setSaved] = useState<string | null>(null)
  async function get(format: ReportFormat) {
    setSaved(null)
    const name = await download.mutateAsync({ periodId, type: 'remuneration', format }).catch(() => null)
    if (name) setSaved(name)
  }
  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex gap-2">
        {(['pdf', 'xlsx', 'csv'] as const).map((f) => (
          <Button key={f} variant="secondary" loading={download.isPending && download.variables?.format === f} onClick={() => void get(f)}>
            <Download className="mr-1.5 size-4" aria-hidden /> {f === 'xlsx' ? 'Excel' : f.toUpperCase()}
          </Button>
        ))}
      </div>
      {download.error && <span className="text-sm text-red-700">{toApiProblem(download.error).detail ?? 'Could not download.'}</span>}
      {saved && <SuccessNotice>Downloaded {saved}.</SuccessNotice>}
    </div>
  )
}

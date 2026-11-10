import { zodResolver } from '@hookform/resolvers/zod'
import { Download, FileSpreadsheet, FileText, IndianRupee, Scale } from 'lucide-react'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { Section, SelectField, SuccessNotice } from '@/components/ui/fields'
import { PageHeader } from '@/components/ui/layout'
import { useChartVersions } from '@/features/allocation/api'
import { useDepartments } from '@/features/departments/api'
import { useHalls } from '@/features/halls/api'
import { usePeriods } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import { useDownloadReport, type GroupBy, type ReportFormat, type ReportType } from './api'

const TYPES: { value: ReportType; label: string; description: string; icon: typeof FileText }[] = [
  { value: 'duty-chart', label: 'Duty chart', description: 'Who invigilates where and when, with open positions.', icon: FileText },
  { value: 'workload', label: 'Workload', description: "Each faculty member's duties against an even share.", icon: FileSpreadsheet },
  { value: 'fairness', label: 'Fairness', description: 'Averages, spread, departments and the trend across periods.', icon: Scale },
  { value: 'remuneration', label: 'Remuneration', description: 'Pay per person and department from duties done.', icon: IndianRupee },
]

const GROUPS: { value: GroupBy; label: string }[] = [
  { value: 'date', label: 'Date' },
  { value: 'session', label: 'Session' },
  { value: 'hall', label: 'Hall' },
  { value: 'faculty', label: 'Faculty' },
  { value: 'department', label: 'Department' },
]

const FORMATS: { value: ReportFormat; label: string }[] = [
  { value: 'pdf', label: 'PDF (print)' },
  { value: 'xlsx', label: 'Excel' },
  { value: 'csv', label: 'CSV' },
]

const schema = z
  .object({
    type: z.enum(['duty-chart', 'workload', 'fairness', 'remuneration']),
    periodId: z.string().min(1, 'Choose an exam period'),
    format: z.enum(['pdf', 'xlsx', 'csv']),
    groupBy: z.enum(['date', 'session', 'hall', 'faculty', 'department']),
    version: z.string(),
    department: z.string(),
    from: z.string(),
    to: z.string(),
    session: z.string().trim().max(20, 'Keep the session label short'),
    hall: z.string(),
  })
  .refine((v) => !v.from || !v.to || v.from <= v.to, { path: ['to'], message: 'The end date is before the start date' })
type Values = z.infer<typeof schema>

/** Exam cell: download the duty chart, workload or fairness figures as PDF, Excel or CSV. */
export function ReportsPage() {
  const periods = usePeriods()
  const departments = useDepartments()
  const halls = useHalls()
  const download = useDownloadReport()
  const [saved, setSaved] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { type: 'duty-chart', periodId: '', format: 'pdf', groupBy: 'date', version: '', department: '', from: '', to: '', session: '', hall: '' },
  })
  const [type, periodId] = useWatch({ control: form.control, name: ['type', 'periodId'] })
  const versions = useChartVersions(type === 'duty-chart' ? periodId : '')
  const errors = form.formState.errors

  async function onSubmit(v: Values) {
    setSaved(null)
    // A failure is shown from download.error below; catching keeps it from becoming an unhandled rejection.
    const name = await download
      .mutateAsync({
        periodId: v.periodId,
        type: v.type,
        format: v.format,
        groupBy: v.groupBy,
        version: v.version ? Number(v.version) : undefined,
        department: v.department,
        from: v.from,
        to: v.to,
        session: v.session,
        hall: v.hall,
      })
      .catch(() => null)
    if (name) setSaved(name)
  }

  return (
    <main className="mx-auto max-w-6xl space-y-6 px-4 py-8">
      <PageHeader title="Reports" description="Download the duty chart, workload or fairness figures of an exam period to print, share or open in Excel." />
      <form className="space-y-6" onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <fieldset>
          <legend className="text-sm font-medium text-slate-700">Report</legend>
          <div className="mt-2 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {TYPES.map((t) => (
              <label
                key={t.value}
                className={`flex cursor-pointer items-start gap-3 rounded-lg border p-3 text-sm ${type === t.value ? 'border-indigo-400 bg-indigo-50' : 'border-slate-200 bg-white'}`}
              >
                <input type="radio" value={t.value} className="mt-1" {...form.register('type')} />
                <span>
                  <span className="flex items-center gap-1.5 font-medium text-slate-900">
                    <t.icon className="size-4" aria-hidden /> {t.label}
                  </span>
                  <span className="text-slate-600">{t.description}</span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        <div className="grid gap-4 sm:grid-cols-3">
          <SelectField
            label="Exam period"
            error={errors.periodId?.message}
            options={[{ value: '', label: periods.isPending ? 'Loading...' : 'Choose a period' }, ...(periods.data ?? []).map((p) => ({ value: p.id, label: p.name }))]}
            {...form.register('periodId', { onChange: () => form.setValue('version', '') })}
          />
          <SelectField label="Format" options={FORMATS} {...form.register('format')} />
          {type === 'duty-chart' && <SelectField label="Group by" options={GROUPS} {...form.register('groupBy')} />}
        </div>

        {type !== 'fairness' && (
          <Section title="Filters" description="Leave a filter empty to include everything.">
            <div className="grid gap-4 sm:grid-cols-3">
              {type === 'duty-chart' && (
                <SelectField
                  label="Chart version"
                  description="The published chart, unless you pick a version (for example a draft to review)."
                  options={[
                    { value: '', label: 'Published version' },
                    ...(versions.data ?? []).map((v) => ({ value: String(v.chartVersion), label: `Version ${v.chartVersion} (${v.status.toLowerCase()})` })),
                  ]}
                  {...form.register('version')}
                />
              )}
              <SelectField
                label="Department"
                options={[{ value: '', label: 'All departments' }, ...(departments.data ?? []).map((d) => ({ value: d.code, label: `${d.code} · ${d.name}` }))]}
                {...form.register('department')}
              />
              {type === 'duty-chart' && (
                <>
                  <SelectField
                    label="Hall"
                    options={[{ value: '', label: 'All halls' }, ...(halls.data ?? []).map((h) => ({ value: h.code, label: `${h.code} · ${h.name}` }))]}
                    {...form.register('hall')}
                  />
                  <TextField label="From" type="date" error={errors.from?.message} {...form.register('from')} />
                  <TextField label="To" type="date" error={errors.to?.message} {...form.register('to')} />
                  <TextField label="Session" placeholder="FN, AN..." error={errors.session?.message} {...form.register('session')} />
                </>
              )}
            </div>
          </Section>
        )}

        {download.error && <ErrorAlert>{toApiProblem(download.error).detail ?? 'Could not create the report.'}</ErrorAlert>}
        {saved && <SuccessNotice>Downloaded {saved}.</SuccessNotice>}
        <Button type="submit" loading={download.isPending}>
          <Download className="mr-2 size-4" aria-hidden /> Download report
        </Button>
      </form>
    </main>
  )
}

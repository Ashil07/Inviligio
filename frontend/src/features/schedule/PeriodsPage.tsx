import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { EditPanel, PageHeader, Table } from '@/components/ui/layout'
import { toApiProblem } from '@/lib/api/client'
import { applyFieldErrors } from '@/lib/forms'
import { formatDate, usePeriods, useSavePeriod, type Period } from './api'

const periodSchema = z
  .object({
    name: z.string().trim().min(1, 'Name is required').max(200),
    startDate: z.string().min(1, 'Start date is required'),
    endDate: z.string().min(1, 'End date is required'),
  })
  .refine((v) => !v.startDate || !v.endDate || v.endDate >= v.startDate, { path: ['endDate'], message: 'Ends before it starts' })
export type PeriodValues = z.infer<typeof periodSchema>

export function PeriodsPage() {
  const periods = usePeriods()
  const [creating, setCreating] = useState(false)
  return (
    <div className="space-y-6">
      <PageHeader
        title="Examinations"
        description="An examination period groups the sessions of one exam season. Inside a period you add sessions, the exams in each session, and the halls students sit in."
        actions={<Button onClick={() => setCreating(true)}>New examination period</Button>}
      />
      {creating && <PeriodForm period={null} onDone={() => setCreating(false)} />}
      {periods.error && <ErrorAlert>{toApiProblem(periods.error).detail ?? 'Could not load periods.'}</ErrorAlert>}
      <Table head={['Period', 'Dates', 'Sessions', 'Status']} empty={periods.data?.length === 0 && 'No examination periods yet.'}>
        {periods.data?.map((p) => (
          <tr key={p.id}>
            <td className="px-4 py-2">
              <Link to={`/admin/exams/${p.id}`} className="font-medium text-indigo-700 hover:underline">{p.name}</Link>
            </td>
            <td className="px-4 py-2 text-slate-600">{formatDate(p.startDate)} - {formatDate(p.endDate)}</td>
            <td className="px-4 py-2 text-slate-600">{p.sessionCount}</td>
            <td className="px-4 py-2 text-slate-600">{p.status.toLowerCase()}</td>
          </tr>
        ))}
      </Table>
    </div>
  )
}

export function PeriodForm({ period, onDone }: { period: Period | null; onDone: () => void }) {
  const save = useSavePeriod()
  const form = useForm<PeriodValues>({
    resolver: zodResolver(periodSchema),
    defaultValues: period ? { name: period.name, startDate: period.startDate, endDate: period.endDate } : { name: '', startDate: '', endDate: '' },
  })
  const [problem, setProblem] = useState<string | null>(null)
  const e = form.formState.errors
  const onSubmit = form.handleSubmit(async (values) => {
    setProblem(null)
    try {
      await save.mutateAsync({ ...values, id: period?.id, version: period?.version })
      onDone()
    } catch (err) {
      const p = toApiProblem(err)
      if (!applyFieldErrors(p, form.setError)) setProblem(p.detail ?? 'Could not save.')
    }
  })
  return (
    <EditPanel title={period ? 'Edit examination period' : 'New examination period'} onClose={onDone}>
      <form onSubmit={(ev) => void onSubmit(ev)} noValidate className="space-y-4">
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Name" placeholder="November 2026 end semester" error={e.name?.message} {...form.register('name')} />
          <TextField label="First day" type="date" error={e.startDate?.message} {...form.register('startDate')} />
          <TextField label="Last day" type="date" error={e.endDate?.message} {...form.register('endDate')} />
        </div>
        <div className="flex gap-2">
          <Button type="submit" loading={save.isPending}>{period ? 'Save changes' : 'Create period'}</Button>
          <Button type="button" variant="secondary" onClick={onDone}>Cancel</Button>
        </div>
      </form>
    </EditPanel>
  )
}

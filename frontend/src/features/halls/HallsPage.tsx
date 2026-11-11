import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { CheckboxField } from '@/components/ui/fields'
import { EditPanel, PageHeader, RowActions, StatusBadge, Table } from '@/components/ui/layout'
import { toApiProblem } from '@/lib/api/client'
import { codeSchema } from '@/lib/codes'
import { applyFieldErrors, asNumber } from '@/lib/forms'
import { useDeleteHall, useHalls, useSaveHall, type Hall } from './api'

const count = (min: number, max: number) =>
  z.number({ error: 'Enter a number' }).int('Whole numbers only').min(min, `At least ${min}`).max(max, `At most ${max}`)

const schema = z.object({
  code: codeSchema,
  name: z.string().trim().min(1, 'Name is required').max(200),
  building: z.string().trim().max(200),
  capacity: count(1, 2000),
  minInvigilators: count(1, 20),
  lab: z.boolean(),
  active: z.boolean(),
})
type FormValues = z.infer<typeof schema>

export function HallsPage() {
  const [showInactive, setShowInactive] = useState(true)
  const halls = useHalls(showInactive)
  const remove = useDeleteHall()
  const [editing, setEditing] = useState<Hall | 'new' | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  async function handleDelete(h: Hall) {
    if (!window.confirm(`Delete hall ${h.code}? This cannot be undone.`)) return
    setProblem(null)
    try {
      await remove.mutateAsync(h.id)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not delete the hall.')
    }
  }

  const activeSeats = halls.data?.filter((h) => h.active).reduce((sum, h) => sum + h.capacity, 0) ?? 0

  return (
    <div className="space-y-6">
      <PageHeader
        title="Examination halls"
        description={`Capacity and the minimum number of invigilators per hall feed staffing requirements. Active seating: ${activeSeats}.`}
        actions={<Button onClick={() => setEditing('new')}>Add hall</Button>}
      />
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {editing && (
        <HallForm key={editing === 'new' ? 'new' : editing.id} hall={editing === 'new' ? null : editing} onDone={() => setEditing(null)} />
      )}
      <CheckboxField label="Show inactive halls" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />
      {halls.error && <ErrorAlert>{toApiProblem(halls.error).detail ?? 'Could not load halls.'}</ErrorAlert>}
      <Table head={['Code', 'Name', 'Building', 'Capacity', 'Min. invigilators', 'Type', 'Status', '']} empty={halls.data?.length === 0 && 'No halls yet.'}>
        {halls.data?.map((h) => (
          <tr key={h.id}>
            <td className="px-4 py-2 font-medium text-neutral-900">{h.code}</td>
            <td className="px-4 py-2 text-neutral-700">{h.name}</td>
            <td className="px-4 py-2 text-neutral-600">{h.building ?? '-'}</td>
            <td className="px-4 py-2 text-neutral-600">{h.capacity}</td>
            <td className="px-4 py-2 text-neutral-600">{h.minInvigilators}</td>
            <td className="px-4 py-2 text-neutral-600">{h.lab ? 'Lab' : 'Hall'}</td>
            <td className="px-4 py-2"><StatusBadge active={h.active} /></td>
            <td className="px-4 py-2"><RowActions label={h.code} onEdit={() => setEditing(h)} onDelete={() => void handleDelete(h)} /></td>
          </tr>
        ))}
      </Table>
    </div>
  )
}

function HallForm({ hall, onDone }: { hall: Hall | null; onDone: () => void }) {
  const save = useSaveHall()
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: hall
      ? { ...hall, building: hall.building ?? '' }
      : { code: '', name: '', building: '', capacity: 30, minInvigilators: 1, lab: false, active: true },
  })
  const [problem, setProblem] = useState<string | null>(null)
  const e = form.formState.errors

  const onSubmit = form.handleSubmit(async (values) => {
    setProblem(null)
    try {
      await save.mutateAsync({ ...values, id: hall?.id, version: hall?.version })
      onDone()
    } catch (err) {
      const p = toApiProblem(err)
      if (!applyFieldErrors(p, form.setError)) setProblem(p.detail ?? 'Could not save.')
    }
  })

  return (
    <EditPanel title={hall ? `Edit ${hall.code}` : 'New hall'} onClose={onDone}>
      <form onSubmit={(ev) => void onSubmit(ev)} noValidate className="space-y-4">
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Code" className="uppercase" error={e.code?.message} {...form.register('code')} />
          <TextField label="Name" error={e.name?.message} {...form.register('name')} />
          <TextField label="Building" hint="Optional" error={e.building?.message} {...form.register('building')} />
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Seating capacity" type="number" min={1} error={e.capacity?.message} {...form.register('capacity', { setValueAs: asNumber })} />
          <TextField
            label="Minimum invigilators"
            type="number"
            min={1}
            error={e.minInvigilators?.message}
            {...form.register('minInvigilators', { setValueAs: asNumber })}
          />
        </div>
        <div className="flex flex-wrap gap-6">
          <CheckboxField label="Laboratory (used for practical exams)" {...form.register('lab')} />
          <CheckboxField label="Active" {...form.register('active')} />
        </div>
        <div className="flex gap-2">
          <Button type="submit" loading={save.isPending}>{hall ? 'Save changes' : 'Create hall'}</Button>
          <Button type="button" variant="secondary" onClick={onDone}>Cancel</Button>
        </div>
      </form>
    </EditPanel>
  )
}

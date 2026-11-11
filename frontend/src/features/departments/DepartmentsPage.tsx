import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { CheckboxField } from '@/components/ui/fields'
import { EditPanel, PageHeader, RowActions, StatusBadge, Table } from '@/components/ui/layout'
import { toApiProblem } from '@/lib/api/client'
import { codeSchema } from '@/lib/codes'
import { applyFieldErrors } from '@/lib/forms'
import { useDeleteDepartment, useDepartments, useSaveDepartment, type Department } from './api'

const schema = z.object({
  code: codeSchema,
  name: z.string().trim().min(1, 'Name is required').max(200),
  active: z.boolean(),
})
type FormValues = z.infer<typeof schema>

export function DepartmentsPage() {
  const [showInactive, setShowInactive] = useState(true)
  const departments = useDepartments(showInactive)
  const remove = useDeleteDepartment()
  const [editing, setEditing] = useState<Department | 'new' | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  async function handleDelete(d: Department) {
    if (!window.confirm(`Delete department ${d.code}? This cannot be undone.`)) return
    setProblem(null)
    try {
      await remove.mutateAsync(d.id)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not delete the department.')
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Departments"
        description="Departments own subjects and faculty. Deactivate a department that is no longer used; delete only mistakes."
        actions={<Button onClick={() => setEditing('new')}>Add department</Button>}
      />
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {editing && (
        <DepartmentForm
          key={editing === 'new' ? 'new' : editing.id}
          department={editing === 'new' ? null : editing}
          onDone={() => setEditing(null)}
        />
      )}
      <CheckboxField label="Show inactive departments" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />
      {departments.error && <ErrorAlert>{toApiProblem(departments.error).detail ?? 'Could not load departments.'}</ErrorAlert>}
      <Table head={['Code', 'Name', 'Status', '']} empty={departments.data?.length === 0 && 'No departments yet.'}>
        {departments.data?.map((d) => (
          <tr key={d.id}>
            <td className="px-4 py-2 font-medium text-neutral-900">{d.code}</td>
            <td className="px-4 py-2 text-neutral-700">{d.name}</td>
            <td className="px-4 py-2"><StatusBadge active={d.active} /></td>
            <td className="px-4 py-2"><RowActions label={d.code} onEdit={() => setEditing(d)} onDelete={() => void handleDelete(d)} /></td>
          </tr>
        ))}
      </Table>
    </div>
  )
}

function DepartmentForm({ department, onDone }: { department: Department | null; onDone: () => void }) {
  const save = useSaveDepartment()
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: department ?? { code: '', name: '', active: true },
  })
  const [problem, setProblem] = useState<string | null>(null)
  const e = form.formState.errors

  const onSubmit = form.handleSubmit(async (values) => {
    setProblem(null)
    try {
      await save.mutateAsync({ ...values, id: department?.id, version: department?.version })
      onDone()
    } catch (err) {
      const p = toApiProblem(err)
      if (!applyFieldErrors(p, form.setError)) setProblem(p.detail ?? 'Could not save.')
    }
  })

  return (
    <EditPanel title={department ? `Edit ${department.code}` : 'New department'} onClose={onDone}>
      <form onSubmit={(ev) => void onSubmit(ev)} noValidate className="space-y-4">
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Code" className="uppercase" error={e.code?.message} {...form.register('code')} />
          <div className="sm:col-span-2">
            <TextField label="Name" error={e.name?.message} {...form.register('name')} />
          </div>
        </div>
        <CheckboxField label="Active" {...form.register('active')} />
        <div className="flex gap-2">
          <Button type="submit" loading={save.isPending}>{department ? 'Save changes' : 'Create department'}</Button>
          <Button type="button" variant="secondary" onClick={onDone}>Cancel</Button>
        </div>
      </form>
    </EditPanel>
  )
}

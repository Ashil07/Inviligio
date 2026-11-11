import { zodResolver } from '@hookform/resolvers/zod'
import { useDeferredValue, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { CheckboxField, SelectField } from '@/components/ui/fields'
import { EditPanel, PageHeader, RowActions, StatusBadge, Table } from '@/components/ui/layout'
import { useDepartments, type Department } from '@/features/departments/api'
import { toApiProblem } from '@/lib/api/client'
import { codeSchema } from '@/lib/codes'
import { applyFieldErrors, asNumberOrNull } from '@/lib/forms'
import { useDeleteSubject, useSaveSubject, useSubjects, type Subject } from './api'

const PAGE_SIZE = 50

const schema = z.object({
  code: codeSchema,
  name: z.string().trim().min(1, 'Name is required').max(200),
  departmentId: z.string().min(1, 'Choose a department'),
  programme: z.string().trim().max(100),
  semester: z.number({ error: 'Enter a number' }).int().min(1, 'At least 1').max(12, 'At most 12').nullable(),
  active: z.boolean(),
})
type FormValues = z.infer<typeof schema>

export function SubjectsPage() {
  const [search, setSearch] = useState('')
  const q = useDeferredValue(search.trim())
  const [departmentId, setDepartmentId] = useState('')
  const [includeInactive, setIncludeInactive] = useState(false)
  const [page, setPage] = useState(0)
  const subjects = useSubjects({ q, departmentId, includeInactive, page, size: PAGE_SIZE })
  const departments = useDepartments(true)
  const remove = useDeleteSubject()
  const [editing, setEditing] = useState<Subject | 'new' | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  async function handleDelete(s: Subject) {
    if (!window.confirm(`Delete subject ${s.code}? This cannot be undone.`)) return
    setProblem(null)
    try {
      await remove.mutateAsync(s.id)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not delete the subject.')
    }
  }

  const data = subjects.data
  const first = data && data.total > 0 ? data.page * data.size + 1 : 0
  const last = data ? data.page * data.size + data.items.length : 0

  return (
    <div className="space-y-6">
      <PageHeader
        title="Subjects"
        description="Subjects examined by the college. A faculty member's subjects decide the own-subject rule."
        actions={
          <Button onClick={() => setEditing('new')} disabled={departments.data?.length === 0}>
            Add subject
          </Button>
        }
      />
      {departments.data?.length === 0 && <p className="text-sm text-neutral-600">Add a department before adding subjects.</p>}
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {editing && departments.data && (
        <SubjectForm
          key={editing === 'new' ? 'new' : editing.id}
          subject={editing === 'new' ? null : editing}
          departments={departments.data}
          onDone={() => setEditing(null)}
        />
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        <TextField
          label="Search"
          placeholder="Code or name"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
        />
        <SelectField
          label="Department"
          value={departmentId}
          onChange={(e) => {
            setDepartmentId(e.target.value)
            setPage(0)
          }}
          options={[{ value: '', label: 'All departments' }, ...(departments.data ?? []).map((d) => ({ value: d.id, label: `${d.code} - ${d.name}` }))]}
        />
        <div className="flex items-end pb-2">
          <CheckboxField
            label="Show inactive"
            checked={includeInactive}
            onChange={(e) => {
              setIncludeInactive(e.target.checked)
              setPage(0)
            }}
          />
        </div>
      </div>

      {subjects.error && <ErrorAlert>{toApiProblem(subjects.error).detail ?? 'Could not load subjects.'}</ErrorAlert>}
      <Table head={['Code', 'Name', 'Department', 'Programme', 'Sem.', 'Status', '']} empty={data?.total === 0 && 'No subjects found.'}>
        {data?.items.map((s) => (
          <tr key={s.id}>
            <td className="px-4 py-2 font-medium text-neutral-900">{s.code}</td>
            <td className="px-4 py-2 text-neutral-700">{s.name}</td>
            <td className="px-4 py-2 text-neutral-600">{s.department.code}</td>
            <td className="px-4 py-2 text-neutral-600">{s.programme ?? '-'}</td>
            <td className="px-4 py-2 text-neutral-600">{s.semester ?? '-'}</td>
            <td className="px-4 py-2"><StatusBadge active={s.active} /></td>
            <td className="px-4 py-2"><RowActions label={s.code} onEdit={() => setEditing(s)} onDelete={() => void handleDelete(s)} /></td>
          </tr>
        ))}
      </Table>
      {data && data.total > PAGE_SIZE && (
        <div className="flex items-center justify-between text-sm text-neutral-600">
          <span>
            {first}-{last} of {data.total}
          </span>
          <div className="flex gap-2">
            <Button variant="secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
            <Button variant="secondary" disabled={last >= data.total} onClick={() => setPage((p) => p + 1)}>Next</Button>
          </div>
        </div>
      )}
    </div>
  )
}

function SubjectForm({ subject, departments, onDone }: { subject: Subject | null; departments: Department[]; onDone: () => void }) {
  const save = useSaveSubject()
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: subject
      ? {
          code: subject.code,
          name: subject.name,
          departmentId: subject.department.id,
          programme: subject.programme ?? '',
          semester: subject.semester,
          active: subject.active,
        }
      : { code: '', name: '', departmentId: '', programme: '', semester: null, active: true },
  })
  const [problem, setProblem] = useState<string | null>(null)
  const e = form.formState.errors
  // Inactive departments can be kept on an existing subject, but not chosen for a new one.
  const options = departments
    .filter((d) => d.active || d.id === subject?.department.id)
    .map((d) => ({ value: d.id, label: `${d.code} - ${d.name}${d.active ? '' : ' (inactive)'}` }))

  const onSubmit = form.handleSubmit(async (values) => {
    setProblem(null)
    try {
      await save.mutateAsync({ ...values, id: subject?.id, version: subject?.version })
      onDone()
    } catch (err) {
      const p = toApiProblem(err)
      if (!applyFieldErrors(p, form.setError)) setProblem(p.detail ?? 'Could not save.')
    }
  })

  return (
    <EditPanel title={subject ? `Edit ${subject.code}` : 'New subject'} onClose={onDone}>
      <form onSubmit={(ev) => void onSubmit(ev)} noValidate className="space-y-4">
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Code" className="uppercase" error={e.code?.message} {...form.register('code')} />
          <div className="sm:col-span-2">
            <TextField label="Name" error={e.name?.message} {...form.register('name')} />
          </div>
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <SelectField
            label="Department"
            error={e.departmentId?.message}
            options={[{ value: '', label: 'Choose...' }, ...options]}
            {...form.register('departmentId')}
          />
          <TextField label="Programme" hint="Optional, e.g. B.Tech" error={e.programme?.message} {...form.register('programme')} />
          <TextField
            label="Semester"
            type="number"
            min={1}
            max={12}
            hint="Optional"
            error={e.semester?.message}
            {...form.register('semester', { setValueAs: asNumberOrNull })}
          />
        </div>
        <CheckboxField label="Active" {...form.register('active')} />
        <div className="flex gap-2">
          <Button type="submit" loading={save.isPending}>{subject ? 'Save changes' : 'Create subject'}</Button>
          <Button type="button" variant="secondary" onClick={onDone}>Cancel</Button>
        </div>
      </form>
    </EditPanel>
  )
}

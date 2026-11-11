import { useDeferredValue, useState } from 'react'
import { Link } from 'react-router'
import { ErrorAlert, TextField } from '@/components/ui/form'
import { CheckboxField, SelectField } from '@/components/ui/fields'
import { PageHeader, StatusBadge, Table } from '@/components/ui/layout'
import { Button } from '@/components/ui/form'
import { useDepartments } from '@/features/departments/api'
import { toApiProblem } from '@/lib/api/client'
import { EMPLOYMENT_LABEL, useFacultySearch, type EmploymentType } from './api'

const PAGE_SIZE = 50

export function FacultyListPage() {
  const [search, setSearch] = useState('')
  const q = useDeferredValue(search.trim())
  const [departmentId, setDepartmentId] = useState('')
  const [employmentType, setEmploymentType] = useState('')
  const [includeInactive, setIncludeInactive] = useState(false)
  const [page, setPage] = useState(0)
  const faculty = useFacultySearch({ q, departmentId, employmentType, includeInactive, page, size: PAGE_SIZE })
  const departments = useDepartments(true)
  const data = faculty.data
  const last = data ? data.page * data.size + data.items.length : 0

  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v)
    setPage(0)
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Faculty"
        description="Everyone who can be given invigilation duties. Their subjects, department and restrictions decide where they may be placed."
        actions={
          <>
            <Link to="/admin/import" className="rounded-md border border-neutral-300 bg-white px-4 py-2 text-sm font-medium text-neutral-700 shadow-sm hover:bg-neutral-50">
              Import CSV
            </Link>
            <Link to="/admin/faculty/new" className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-primary-700">
              Add faculty
            </Link>
          </>
        }
      />
      <div className="grid gap-4 sm:grid-cols-4">
        <TextField label="Search" placeholder="Name, code or email" value={search} onChange={(e) => reset(setSearch)(e.target.value)} />
        <SelectField
          label="Department"
          value={departmentId}
          onChange={(e) => reset(setDepartmentId)(e.target.value)}
          options={[{ value: '', label: 'All departments' }, ...(departments.data ?? []).map((d) => ({ value: d.id, label: d.code }))]}
        />
        <SelectField
          label="Employment"
          value={employmentType}
          onChange={(e) => reset(setEmploymentType)(e.target.value)}
          options={[{ value: '', label: 'All types' }, ...Object.entries(EMPLOYMENT_LABEL).map(([value, label]) => ({ value, label }))]}
        />
        <div className="flex items-end pb-2">
          <CheckboxField label="Show inactive" checked={includeInactive} onChange={(e) => reset(setIncludeInactive)(e.target.checked)} />
        </div>
      </div>
      {faculty.error && <ErrorAlert>{toApiProblem(faculty.error).detail ?? 'Could not load faculty.'}</ErrorAlert>}
      <Table
        head={['Code', 'Name', 'Department', 'Designation', 'Type', 'Subjects', 'Chief', 'Status']}
        empty={data?.total === 0 && 'No faculty found.'}
      >
        {data?.items.map((f) => (
          <tr key={f.id}>
            <td className="px-4 py-2 font-medium text-neutral-900">{f.employeeCode}</td>
            <td className="px-4 py-2">
              <Link to={`/admin/faculty/${f.id}`} className="link">
                {f.fullName}
              </Link>
            </td>
            <td className="px-4 py-2 text-neutral-600">{f.department.code}</td>
            <td className="px-4 py-2 text-neutral-600">{f.designation ?? '-'}</td>
            <td className="px-4 py-2 text-neutral-600">{EMPLOYMENT_LABEL[f.employmentType as EmploymentType]}</td>
            <td className="px-4 py-2 text-neutral-600">{f.subjectCount}</td>
            <td className="px-4 py-2 text-neutral-600">{f.chiefEligible ? 'Yes' : '-'}</td>
            <td className="px-4 py-2"><StatusBadge active={f.active} /></td>
          </tr>
        ))}
      </Table>
      {data && data.total > PAGE_SIZE && (
        <div className="flex items-center justify-between text-sm text-neutral-600">
          <span>
            {data.page * data.size + 1}-{last} of {data.total}
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

import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { Button, ErrorAlert } from '@/components/ui/form'
import { StatusBadge } from '@/components/ui/layout'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { toApiProblem } from '@/lib/api/client'
import { AccessSection } from './AccessSection'
import { useDeleteFaculty, useFacultyMember } from './api'
import { FacultyForm } from './FacultyForm'
import { RestrictionsSection } from './RestrictionsSection'

/** `/admin/faculty/new` creates; `/admin/faculty/:id` edits, with restrictions and sign-in access. */
export function FacultyDetailPage() {
  const params = useParams()
  const isNew = params.id === 'new'
  const query = useFacultyMember(isNew ? undefined : params.id)
  const navigate = useNavigate()
  const remove = useDeleteFaculty()
  const [problem, setProblem] = useState<string | null>(null)

  if (!isNew && query.error) return <ErrorAlert>{toApiProblem(query.error).detail ?? 'Could not load this faculty member.'}</ErrorAlert>
  if (!isNew && !query.data) return <FullPageSpinner label="Loading faculty member" />
  const faculty = isNew ? null : query.data!

  async function handleDelete() {
    if (!faculty || !window.confirm(`Delete ${faculty.fullName}? This cannot be undone.`)) return
    setProblem(null)
    try {
      await remove.mutateAsync(faculty.id)
      navigate('/admin/faculty', { replace: true })
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not delete.')
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <Link to="/admin/faculty" className="text-sm link">
          ← All faculty
        </Link>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold text-neutral-900 sm:text-3xl">{faculty ? faculty.fullName : 'New faculty member'}</h1>
          {faculty && <StatusBadge active={faculty.active} />}
        </div>
        {faculty && (
          <p className="mt-1 text-sm text-neutral-600">
            {faculty.employeeCode} · {faculty.department.code}
          </p>
        )}
      </div>
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      <FacultyForm
        key={faculty?.id ?? 'new'}
        faculty={faculty}
        onSaved={(saved) => {
          if (isNew) navigate(`/admin/faculty/${saved.id}`, { replace: true })
        }}
      />
      {faculty && (
        <>
          <RestrictionsSection faculty={faculty} />
          <AccessSection faculty={faculty} />
          <div className="border-t border-neutral-200 pt-4">
            <Button type="button" variant="secondary" onClick={() => void handleDelete()} className="text-red-700">
              Delete faculty member
            </Button>
            <p className="mt-1 text-xs text-neutral-500">Only possible before they have a login. Otherwise mark them inactive.</p>
          </div>
        </>
      )}
    </div>
  )
}

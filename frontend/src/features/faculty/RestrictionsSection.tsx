import { useState } from 'react'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { Section, SelectField } from '@/components/ui/fields'
import { useDepartments } from '@/features/departments/api'
import { useHalls } from '@/features/halls/api'
import { toApiProblem } from '@/lib/api/client'
import { useAddRestriction, useRemoveRestriction, type FacultyDetail, type Restriction, type RestrictionType } from './api'

const TYPES: { value: RestrictionType; label: string }[] = [
  { value: 'EXCLUDE_HALL', label: 'Never in a particular hall' },
  { value: 'EXCLUDE_DEPARTMENT_EXAMS', label: "Never for a department's exams" },
  { value: 'NOT_CHIEF', label: 'Never chief invigilator' },
  { value: 'MAX_DUTIES_PER_DAY', label: 'Fewer duties per day' },
  { value: 'MAX_DUTIES_PER_PERIOD', label: 'Fewer duties per exam period' },
]

function describeRestriction(r: Restriction): string {
  switch (r.type) {
    case 'EXCLUDE_HALL':
      return `Never in hall ${r.hall?.code}`
    case 'EXCLUDE_DEPARTMENT_EXAMS':
      return `Never for ${r.department?.code} exams`
    case 'NOT_CHIEF':
      return 'Never chief invigilator'
    case 'MAX_DUTIES_PER_DAY':
      return `At most ${r.maxValue} duties per day`
    case 'MAX_DUTIES_PER_PERIOD':
      return `At most ${r.maxValue} duties per exam period`
  }
}

/** Per-person strict rules. They apply in addition to the college policy and always win. */
export function RestrictionsSection({ faculty }: { faculty: FacultyDetail }) {
  const add = useAddRestriction(faculty.id)
  const remove = useRemoveRestriction(faculty.id)
  const halls = useHalls()
  const departments = useDepartments()
  const [type, setType] = useState<RestrictionType>('EXCLUDE_HALL')
  const [target, setTarget] = useState('')
  const [maxValue, setMaxValue] = useState('1')
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)

  async function submit() {
    setProblem(null)
    try {
      await add.mutateAsync({
        type,
        hallId: type === 'EXCLUDE_HALL' ? target || undefined : undefined,
        departmentId: type === 'EXCLUDE_DEPARTMENT_EXAMS' ? target || undefined : undefined,
        maxValue: type.startsWith('MAX_') ? Number(maxValue) : undefined,
        reason: reason || undefined,
      })
      setTarget('')
      setReason('')
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not add the restriction.')
    }
  }

  async function handleRemove(r: Restriction) {
    setProblem(null)
    try {
      await remove.mutateAsync(r.id)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not remove the restriction.')
    }
  }

  return (
    <Section title="Restrictions" description="Strict, per-person rules. Allocation never breaks them, whatever the college policy says.">
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {faculty.restrictions.length === 0 ? (
        <p className="text-sm text-slate-500">No restrictions.</p>
      ) : (
        <ul className="divide-y divide-slate-100 rounded-md border border-slate-200">
          {faculty.restrictions.map((r) => (
            <li key={r.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
              <span>
                <span className="font-medium text-slate-800">{describeRestriction(r)}</span>
                {r.reason && <span className="text-slate-500"> - {r.reason}</span>}
              </span>
              <button type="button" className="text-red-700 hover:underline" onClick={() => void handleRemove(r)}>
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="grid gap-4 sm:grid-cols-4">
        <SelectField
          label="Add restriction"
          value={type}
          onChange={(e) => {
            setType(e.target.value as RestrictionType)
            setTarget('')
          }}
          options={TYPES}
        />
        {type === 'EXCLUDE_HALL' && (
          <SelectField
            label="Hall"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            options={[{ value: '', label: 'Choose...' }, ...(halls.data ?? []).map((h) => ({ value: h.id, label: `${h.code} - ${h.name}` }))]}
          />
        )}
        {type === 'EXCLUDE_DEPARTMENT_EXAMS' && (
          <SelectField
            label="Department"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            options={[{ value: '', label: 'Choose...' }, ...(departments.data ?? []).map((d) => ({ value: d.id, label: d.code }))]}
          />
        )}
        {type.startsWith('MAX_') && (
          <TextField label="Maximum" type="number" min={0} max={200} value={maxValue} onChange={(e) => setMaxValue(e.target.value)} />
        )}
        <TextField label="Reason" hint="Optional" value={reason} onChange={(e) => setReason(e.target.value)} />
      </div>
      <div>
        <Button type="button" variant="secondary" loading={add.isPending} onClick={() => void submit()}>
          Add restriction
        </Button>
      </div>
    </Section>
  )
}

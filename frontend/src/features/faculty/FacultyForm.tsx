import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { CheckboxField, Section, SelectField, SuccessNotice } from '@/components/ui/fields'
import { useDepartments } from '@/features/departments/api'
import { toApiProblem } from '@/lib/api/client'
import { codeSchema } from '@/lib/codes'
import { applyFieldErrors, asNumber } from '@/lib/forms'
import { EMPLOYMENT_LABEL, useSaveFaculty, type FacultyDetail } from './api'
import { SubjectPicker } from './SubjectPicker'

const today = () => new Date().toISOString().slice(0, 10)

const schema = z.object({
  employeeCode: codeSchema,
  fullName: z.string().trim().min(1, 'Name is required').max(200),
  departmentId: z.string().min(1, 'Choose a department'),
  email: z.union([z.literal(''), z.string().trim().pipe(z.email('Enter a valid email address'))]),
  phone: z.string().trim().max(30),
  designation: z.string().trim().max(100),
  employmentType: z.enum(['REGULAR', 'VISITING', 'GUEST', 'NON_TEACHING']),
  joinedOn: z.string().refine((v) => v === '' || v <= today(), 'Cannot be in the future'),
  chiefEligible: z.boolean(),
  dutyWeight: z.number({ error: 'Enter a number' }).min(0.1, 'At least 0.10').max(2, 'At most 2.00'),
  active: z.boolean(),
  subjects: z.array(z.object({ id: z.string(), code: z.string(), name: z.string() })).max(100),
})
type FormValues = z.infer<typeof schema>

function defaults(f: FacultyDetail | null): FormValues {
  return f
    ? {
        employeeCode: f.employeeCode,
        fullName: f.fullName,
        departmentId: f.department.id,
        email: f.email ?? '',
        phone: f.phone ?? '',
        designation: f.designation ?? '',
        employmentType: f.employmentType,
        joinedOn: f.joinedOn ?? '',
        chiefEligible: f.chiefEligible,
        dutyWeight: f.dutyWeight,
        active: f.active,
        subjects: f.subjects,
      }
    : {
        employeeCode: '',
        fullName: '',
        departmentId: '',
        email: '',
        phone: '',
        designation: '',
        employmentType: 'REGULAR',
        joinedOn: '',
        chiefEligible: false,
        dutyWeight: 1,
        active: true,
        subjects: [],
      }
}

export function FacultyForm({ faculty, onSaved }: { faculty: FacultyDetail | null; onSaved: (saved: FacultyDetail) => void }) {
  const departments = useDepartments(true)
  const save = useSaveFaculty()
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: defaults(faculty) })
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const e = form.formState.errors
  const departmentOptions = (departments.data ?? [])
    .filter((d) => d.active || d.id === faculty?.department.id)
    .map((d) => ({ value: d.id, label: `${d.code} - ${d.name}${d.active ? '' : ' (inactive)'}` }))

  const onSubmit = form.handleSubmit(async ({ subjects, joinedOn, ...values }) => {
    setProblem(null)
    setSaved(false)
    try {
      const result = await save.mutateAsync({
        ...values,
        joinedOn: joinedOn || null,
        subjectIds: subjects.map((s) => s.id),
        id: faculty?.id,
        version: faculty?.version,
      })
      form.reset(defaults(result))
      setSaved(true)
      onSaved(result)
    } catch (err) {
      const p = toApiProblem(err)
      if (!applyFieldErrors(p, form.setError)) setProblem(p.detail ?? 'Could not save.')
    }
  })

  return (
    <form onSubmit={(ev) => void onSubmit(ev)} noValidate>
      <Section title="Details">
        {saved && <SuccessNotice>Saved.</SuccessNotice>}
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Employee code" className="uppercase" error={e.employeeCode?.message} {...form.register('employeeCode')} />
          <div className="sm:col-span-2">
            <TextField label="Full name" error={e.fullName?.message} {...form.register('fullName')} />
          </div>
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <SelectField
            label="Department"
            error={e.departmentId?.message}
            options={[{ value: '', label: 'Choose...' }, ...departmentOptions]}
            {...form.register('departmentId')}
          />
          <TextField label="Designation" hint="e.g. Assistant Professor" error={e.designation?.message} {...form.register('designation')} />
          <SelectField
            label="Employment type"
            options={Object.entries(EMPLOYMENT_LABEL).map(([value, label]) => ({ value, label }))}
            {...form.register('employmentType')}
          />
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Email" type="email" hint="Needed to invite them to sign in." error={e.email?.message} {...form.register('email')} />
          <TextField label="Phone" type="tel" error={e.phone?.message} {...form.register('phone')} />
          <TextField label="Joined on" type="date" hint="Used for seniority." error={e.joinedOn?.message} {...form.register('joinedOn')} />
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField
            label="Duty weight"
            type="number"
            step="0.05"
            min={0.1}
            max={2}
            hint="1.00 = normal share; 0.50 = half the duties."
            error={e.dutyWeight?.message}
            {...form.register('dutyWeight', { setValueAs: asNumber })}
          />
          <div className="flex flex-col justify-end gap-3 pb-2 sm:col-span-2">
            <CheckboxField label="Eligible to be chief invigilator" {...form.register('chiefEligible')} />
            <CheckboxField label="Active (can receive duties)" {...form.register('active')} />
          </div>
        </div>
        <Controller
          control={form.control}
          name="subjects"
          render={({ field }) => <SubjectPicker value={field.value} onChange={field.onChange} />}
        />
        <div>
          <Button type="submit" loading={save.isPending}>{faculty ? 'Save changes' : 'Create faculty member'}</Button>
        </div>
      </Section>
    </form>
  )
}

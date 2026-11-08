import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Button, ErrorAlert } from '@/components/ui/form'
import { SuccessNotice } from '@/components/ui/fields'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { toApiProblem } from '@/lib/api/client'
import { applyFieldErrors } from '@/lib/forms'
import { useCollege, useUpdateCollege, type CollegeDetails } from './api'
import { InstitutionFields } from './InstitutionFields'
import { collegeProfileSchema, profileFormValues, type CollegeProfile } from './schema'

export function CollegeSettingsPage() {
  const college = useCollege()
  if (college.error) return <ErrorAlert>{toApiProblem(college.error).detail ?? 'Could not load the college.'}</ErrorAlert>
  if (!college.data) return <FullPageSpinner label="Loading college" />
  return <CollegeForm college={college.data} />
}

function CollegeForm({ college }: { college: CollegeDetails }) {
  const form = useForm<CollegeProfile>({ resolver: zodResolver(collegeProfileSchema), defaultValues: profileFormValues(college) })
  const update = useUpdateCollege()
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const onSubmit = form.handleSubmit(async (profile) => {
    setProblem(null)
    setSaved(false)
    try {
      const details = await update.mutateAsync(profile)
      form.reset(profileFormValues(details))
      setSaved(true)
    } catch (e) {
      const p = toApiProblem(e)
      if (!applyFieldErrors(p, form.setError)) setProblem(p.detail ?? 'Could not save.')
    }
  })

  return (
    <form onSubmit={(e) => void onSubmit(e)} noValidate className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold text-slate-900">College details</h1>
        <Button type="submit" loading={update.isPending} disabled={!form.formState.isDirty}>
          Save changes
        </Button>
      </div>
      {saved && <SuccessNotice>College details saved.</SuccessNotice>}
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      <InstitutionFields form={form} collegeCode={college.code} />
    </form>
  )
}

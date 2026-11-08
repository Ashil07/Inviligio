import { zodResolver } from '@hookform/resolvers/zod'
import { Check } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Navigate, useNavigate } from 'react-router'
import { Button, ErrorAlert } from '@/components/ui/form'
import { Section } from '@/components/ui/fields'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useCollege, useCompleteOnboarding, useUpdateCollege, type CollegeDetails } from '@/features/college/api'
import { InstitutionFields } from '@/features/college/InstitutionFields'
import { collegeProfileSchema, profileFormValues, type CollegeProfile } from '@/features/college/schema'
import { usePolicy, useSavePolicy, type PolicyView } from '@/features/policy/api'
import {
  AvailabilitySection,
  FacultyRulesSection,
  StaffingSection,
  SwapSection,
  WorkloadSection,
} from '@/features/policy/PolicySections'
import { describePolicy, policySettingsSchema, type PolicySection, type PolicySettings } from '@/features/policy/schema'
import { toApiProblem } from '@/lib/api/client'
import { applyFieldErrors } from '@/lib/forms'

const STEPS: { title: string; section?: PolicySection }[] = [
  { title: 'Institution' },
  { title: 'Faculty rules', section: 'faculty' },
  { title: 'Workload', section: 'workload' },
  { title: 'Hall staffing', section: 'staffing' },
  { title: 'Availability', section: 'availability' },
  { title: 'Swaps', section: 'swaps' },
  { title: 'Review' },
]

/** Guided first-time setup: institution details, then each policy area, then review and finish. */
export function OnboardingWizard() {
  const college = useCollege()
  const policy = usePolicy()
  const error = college.error ?? policy.error
  if (error) {
    return (
      <main className="mx-auto max-w-3xl px-4 py-8">
        <ErrorAlert>{toApiProblem(error).detail ?? 'Could not load the college setup.'}</ErrorAlert>
      </main>
    )
  }
  if (!college.data || !policy.data) return <FullPageSpinner label="Loading college setup" />
  if (college.data.status !== 'ONBOARDING') return <Navigate to="/admin" replace />
  return <Wizard college={college.data} policy={policy.data} />
}

function Wizard({ college, policy }: { college: CollegeDetails; policy: PolicyView }) {
  const navigate = useNavigate()
  const [step, setStep] = useState(0)
  const profileForm = useForm<CollegeProfile>({
    resolver: zodResolver(collegeProfileSchema),
    defaultValues: profileFormValues(college),
  })
  const policyForm = useForm<PolicySettings>({
    resolver: zodResolver(policySettingsSchema),
    defaultValues: policy.settings,
  })
  const updateCollege = useUpdateCollege()
  const savePolicy = useSavePolicy()
  const complete = useCompleteOnboarding()
  const [problem, setProblem] = useState<string | null>(null)
  const current = STEPS[step]!
  const isLast = step === STEPS.length - 1

  async function next() {
    setProblem(null)
    if (step === 0) {
      const valid = await profileForm.trigger()
      if (!valid) return
      try {
        await updateCollege.mutateAsync(profileForm.getValues())
      } catch (e) {
        const p = toApiProblem(e)
        if (!applyFieldErrors(p, profileForm.setError)) setProblem(p.detail ?? 'Could not save the institution details.')
        return
      }
    } else if (current.section) {
      if (!(await policyForm.trigger(current.section))) return
    }
    setStep((s) => s + 1)
  }

  async function finish() {
    setProblem(null)
    if (!(await policyForm.trigger())) return
    try {
      // On a retry after a failed completion, build on the version this wizard already saved.
      const basedOnVersion = savePolicy.data?.version ?? policy.version
      await savePolicy.mutateAsync({ basedOnVersion, settings: policyForm.getValues() })
      await complete.mutateAsync()
      navigate('/admin', { replace: true })
    } catch (e) {
      const p = toApiProblem(e)
      applyFieldErrors(p, policyForm.setError, 'settings.')
      setProblem(p.detail ?? 'Could not finish setup.')
    }
  }

  const busy = updateCollege.isPending || savePolicy.isPending || complete.isPending

  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <h1 className="text-2xl font-semibold text-slate-900">Set up your college</h1>
      <p className="mt-1 text-slate-600">
        These rules decide how invigilation duties are allocated. Every setting can be changed later; each change is
        kept as a new policy version.
      </p>

      <ol className="mt-6 flex flex-wrap gap-2" aria-label="Setup steps">
        {STEPS.map((s, i) => (
          <li
            key={s.title}
            aria-current={i === step ? 'step' : undefined}
            className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${
              i === step ? 'bg-indigo-600 text-white' : i < step ? 'bg-indigo-50 text-indigo-700' : 'bg-slate-100 text-slate-500'
            }`}
          >
            {i < step && <Check className="size-3" aria-hidden />}
            {i + 1}. {s.title}
          </li>
        ))}
      </ol>

      <div className="mt-6 space-y-4">
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        {step === 0 && <InstitutionFields form={profileForm} collegeCode={college.code} />}
        {current.section === 'faculty' && <FacultyRulesSection form={policyForm} />}
        {current.section === 'workload' && <WorkloadSection form={policyForm} />}
        {current.section === 'staffing' && <StaffingSection form={policyForm} />}
        {current.section === 'availability' && <AvailabilitySection form={policyForm} />}
        {current.section === 'swaps' && <SwapSection form={policyForm} />}
        {isLast && <Review settings={policyForm.getValues()} />}
      </div>

      <div className="mt-6 flex justify-between gap-3">
        <Button type="button" variant="secondary" disabled={step === 0 || busy} onClick={() => setStep((s) => s - 1)}>
          Back
        </Button>
        {isLast ? (
          <Button type="button" loading={busy} onClick={() => void finish()}>
            Save policy and finish setup
          </Button>
        ) : (
          <Button type="button" loading={busy} onClick={() => void next()}>
            {step === 0 ? 'Save and continue' : 'Continue'}
          </Button>
        )}
      </div>
    </main>
  )
}

function Review({ settings }: { settings: PolicySettings }) {
  return (
    <Section title="Review" description="Check the rules below, then save them as policy version 1.">
      {describePolicy(settings).map((g) => (
        <div key={g.group}>
          <h3 className="text-sm font-semibold text-slate-800">{g.group}</h3>
          <ul className="mt-1 list-disc space-y-0.5 pl-5 text-sm text-slate-600">
            {g.items.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      ))}
    </Section>
  )
}

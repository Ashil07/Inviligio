import type { UseFormReturn } from 'react-hook-form'
import { TextField } from '@/components/ui/form'
import { CheckboxField, Section, SelectField } from '@/components/ui/fields'
import { asNumber, asNumberOrNull } from '@/lib/forms'
import type { PolicySettings } from './schema'

/**
 * Form sections for the policy, shared by the onboarding wizard, the policy settings page and the
 * read-only view of past versions. Each takes the same react-hook-form instance.
 */
interface SectionProps {
  form: UseFormReturn<PolicySettings>
  disabled?: boolean
}

const MODE_OPTIONS = [
  { value: 'HARD', label: 'Strict: never exceed' },
  { value: 'SOFT', label: 'Preference: avoid when possible' },
  { value: 'OFF', label: 'Off: no limit' },
]

export function FacultyRulesSection({ form, disabled }: SectionProps) {
  const { register, watch } = form
  const chief = watch('faculty.chiefInvigilator')
  return (
    <Section title="Faculty and subject rules" description="Who may invigilate which examinations.">
      <CheckboxField
        label="Faculty may invigilate a subject they teach"
        description="When unticked, nobody is placed in a hall where a subject they teach is being examined."
        disabled={disabled}
        {...register('faculty.allowOwnSubject')}
      />
      <SelectField
        label="Examinations conducted by the faculty member's own department"
        disabled={disabled}
        options={[
          { value: 'ALLOW', label: 'Allowed' },
          { value: 'AVOID', label: 'Avoid when possible' },
          { value: 'FORBID', label: 'Not allowed' },
        ]}
        {...register('faculty.ownDepartmentExams')}
      />
      <CheckboxField label="Visiting faculty are eligible for duties" disabled={disabled} {...register('faculty.visitingFacultyEligible')} />
      <CheckboxField
        label="Senior faculty receive fewer duties"
        description="Seniority lowers a faculty member's share of duties. It is a preference, not a strict rule."
        disabled={disabled}
        {...register('faculty.seniorsGetFewerDuties')}
      />
      <SelectField
        label="Chief invigilator"
        disabled={disabled}
        options={[
          { value: 'NONE', label: 'Not used' },
          { value: 'PER_HALL', label: 'One chief invigilator in every hall' },
        ]}
        {...register('faculty.chiefInvigilator')}
      />
      {chief === 'PER_HALL' && (
        <>
          <CheckboxField
            label="The chief invigilator counts towards the hall's required invigilators"
            disabled={disabled}
            {...register('faculty.chiefCountsTowardsStaffing')}
          />
          <CheckboxField
            label="Prefer senior faculty as chief invigilators"
            disabled={disabled}
            {...register('faculty.preferSeniorsAsChief')}
          />
        </>
      )}
    </Section>
  )
}

export function WorkloadSection({ form, disabled }: SectionProps) {
  const { register, watch, formState } = form
  const e = formState.errors.workload
  const sessionsMode = watch('workload.consecutiveSessions.mode')
  const daysMode = watch('workload.consecutiveDays.mode')
  return (
    <Section title="Workload" description="Limits that protect faculty from overload, and how fairness is balanced.">
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Maximum duties per day"
          type="number"
          min={1}
          max={6}
          disabled={disabled}
          error={e?.maxDutiesPerDay?.message}
          {...register('workload.maxDutiesPerDay', { setValueAs: asNumber })}
        />
        <TextField
          label="Minimum rest between duties (minutes)"
          type="number"
          min={0}
          disabled={disabled}
          error={e?.minRestMinutes?.message}
          {...register('workload.minRestMinutes', { setValueAs: asNumber })}
        />
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField label="Consecutive sessions" disabled={disabled} options={MODE_OPTIONS} {...register('workload.consecutiveSessions.mode')} />
        <TextField
          label="Maximum consecutive sessions"
          type="number"
          min={1}
          disabled={disabled || sessionsMode === 'OFF'}
          error={e?.consecutiveSessions?.max?.message}
          {...register('workload.consecutiveSessions.max', { setValueAs: asNumber })}
        />
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField label="Consecutive examination days with duty" disabled={disabled} options={MODE_OPTIONS} {...register('workload.consecutiveDays.mode')} />
        <TextField
          label="Maximum consecutive days"
          type="number"
          min={1}
          disabled={disabled || daysMode === 'OFF'}
          error={e?.consecutiveDays?.max?.message}
          {...register('workload.consecutiveDays.max', { setValueAs: asNumber })}
        />
      </div>
      <TextField
        label="Maximum duties per examination period"
        hint="Leave empty for no cap."
        type="number"
        min={1}
        disabled={disabled}
        error={e?.maxDutiesPerPeriod?.message}
        {...register('workload.maxDutiesPerPeriod', { setValueAs: asNumberOrNull })}
      />
      <div className="grid gap-4 sm:grid-cols-3">
        <TextField
          label="Equal distribution importance (0-10)"
          type="number"
          min={0}
          max={10}
          disabled={disabled}
          error={e?.equalDistributionWeight?.message}
          {...register('workload.equalDistributionWeight', { setValueAs: asNumber })}
        />
        <TextField
          label="Previous workload counted (%)"
          hint="How much earlier periods' duties count when balancing."
          type="number"
          min={0}
          max={100}
          disabled={disabled}
          error={e?.historicalWorkloadPercent?.message}
          {...register('workload.historicalWorkloadPercent', { setValueAs: asNumber })}
        />
        <TextField
          label="Avoid repeating the same hall (0-10)"
          type="number"
          min={0}
          max={10}
          disabled={disabled}
          error={e?.avoidRepeatHallWeight?.message}
          {...register('workload.avoidRepeatHallWeight', { setValueAs: asNumber })}
        />
      </div>
    </Section>
  )
}

export function StaffingSection({ form, disabled }: SectionProps) {
  const { register, formState } = form
  const e = formState.errors.staffing
  return (
    <Section
      title="Hall staffing"
      description="Required invigilators per hall = the larger of the hall minimum and students ÷ ratio, rounded up. Individual halls can be adjusted later."
    >
      <div className="grid gap-4 sm:grid-cols-3">
        <TextField
          label="Students per invigilator"
          type="number"
          min={1}
          disabled={disabled}
          error={e?.studentsPerInvigilator?.message}
          {...register('staffing.studentsPerInvigilator', { setValueAs: asNumber })}
        />
        <TextField
          label="Students per invigilator (practicals)"
          hint="Empty = same as above."
          type="number"
          min={1}
          disabled={disabled}
          error={e?.practicalStudentsPerInvigilator?.message}
          {...register('staffing.practicalStudentsPerInvigilator', { setValueAs: asNumberOrNull })}
        />
        <TextField
          label="Minimum invigilators per hall"
          type="number"
          min={1}
          disabled={disabled}
          error={e?.minInvigilatorsPerHall?.message}
          {...register('staffing.minInvigilatorsPerHall', { setValueAs: asNumber })}
        />
      </div>
    </Section>
  )
}

export function AvailabilitySection({ form, disabled }: SectionProps) {
  const { register } = form
  return (
    <Section title="Availability and leave" description="Approved leave is always respected by allocation.">
      <CheckboxField label="Faculty can mark a full day as unavailable" disabled={disabled} {...register('availability.allowFullDayUnavailability')} />
      <CheckboxField label="Faculty can mark a single session as unavailable" disabled={disabled} {...register('availability.allowSessionUnavailability')} />
      <CheckboxField
        label="Leave and unavailability need HOD approval"
        description="When unticked, requests take effect immediately."
        disabled={disabled}
        {...register('availability.leaveRequiresHodApproval')}
      />
      <CheckboxField
        label="Exam cell may override unavailability"
        description="Each override must state a reason and is recorded in the audit log."
        disabled={disabled}
        {...register('availability.adminCanOverrideUnavailability')}
      />
    </Section>
  )
}

export function SwapSection({ form, disabled }: SectionProps) {
  const { register, watch } = form
  const enabled = watch('swaps.swapsEnabled')
  return (
    <Section title="Duty swaps" description="Every swap is re-checked against all strict rules before it is applied.">
      <CheckboxField label="Faculty may request duty swaps" disabled={disabled} {...register('swaps.swapsEnabled')} />
      {enabled && (
        <>
          <CheckboxField label="The other faculty member must accept" disabled={disabled} {...register('swaps.recipientMustAccept')} />
          <CheckboxField label="HOD approval is required" disabled={disabled} {...register('swaps.hodApprovalRequired')} />
        </>
      )}
      <CheckboxField label="Exam cell may reassign duties directly" disabled={disabled} {...register('swaps.adminCanReassignDirectly')} />
    </Section>
  )
}

export function AllPolicySections(props: SectionProps) {
  return (
    <div className="space-y-6">
      <FacultyRulesSection {...props} />
      <WorkloadSection {...props} />
      <StaffingSection {...props} />
      <AvailabilitySection {...props} />
      <SwapSection {...props} />
    </div>
  )
}

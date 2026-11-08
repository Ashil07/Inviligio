import { useMemo } from 'react'
import type { UseFormReturn } from 'react-hook-form'
import { TextField } from '@/components/ui/form'
import { Section, SelectField } from '@/components/ui/fields'
import type { CollegeProfile } from './schema'

function timeZoneOptions(current: string) {
  const zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : []
  const all = zones.includes(current) ? zones : [current, ...zones]
  return all.map((z) => ({ value: z, label: z.replaceAll('_', ' ') }))
}

export function InstitutionFields({ form, collegeCode }: { form: UseFormReturn<CollegeProfile>; collegeCode: string }) {
  const { register, formState, getValues } = form
  const e = formState.errors
  const zones = useMemo(() => timeZoneOptions(getValues('timeZone')), [getValues])
  return (
    <Section title="Institution" description={`College code ${collegeCode} is permanent.`}>
      <TextField label="College name" error={e.name?.message} {...register('name')} />
      <TextField
        label="Affiliation"
        hint="University or board the college is affiliated to."
        error={e.affiliation?.message}
        {...register('affiliation')}
      />
      <TextField label="Address" error={e.address?.message} {...register('address')} />
      <div className="grid gap-4 sm:grid-cols-3">
        <TextField label="City" error={e.city?.message} {...register('city')} />
        <TextField label="State" error={e.state?.message} {...register('state')} />
        <TextField label="PIN code" error={e.postalCode?.message} {...register('postalCode')} />
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Exam cell email" type="email" error={e.contactEmail?.message} {...register('contactEmail')} />
        <TextField label="Exam cell phone" type="tel" error={e.contactPhone?.message} {...register('contactPhone')} />
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Academic year" hint="For example 2026-27." error={e.academicYear?.message} {...register('academicYear')} />
        <SelectField label="Time zone" options={zones} error={e.timeZone?.message} {...register('timeZone')} />
      </div>
    </Section>
  )
}

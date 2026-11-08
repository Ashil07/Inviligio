import { z } from 'zod'
import type { CollegeDetails } from './api'

const optional = (max: number) => z.string().trim().max(max)

export const collegeProfileSchema = z.object({
  name: z.string().trim().min(1, 'College name is required').max(200),
  affiliation: z.string().trim().min(1, 'Affiliation is required').max(300),
  address: z.string().trim().min(1, 'Address is required').max(500),
  city: z.string().trim().min(1, 'City is required').max(100),
  state: z.string().trim().min(1, 'State is required').max(100),
  postalCode: optional(20),
  contactEmail: z.union([z.literal(''), z.string().trim().pipe(z.email('Enter a valid email address'))]),
  contactPhone: optional(30),
  academicYear: z
    .string()
    .trim()
    .regex(/^\d{4}-\d{2}$/, 'Use the form 2026-27')
    .refine((v) => {
      const [start, end] = v.split('-').map(Number)
      return start !== undefined && end !== undefined && (start + 1) % 100 === end
    }, 'The second year must follow the first, e.g. 2026-27'),
  timeZone: z.string().min(1, 'Time zone is required'),
})

export type CollegeProfile = z.infer<typeof collegeProfileSchema>

export function profileFormValues(details: CollegeDetails): CollegeProfile {
  const p = details.profile
  return {
    name: p.name,
    affiliation: p.affiliation ?? '',
    address: p.address ?? '',
    city: p.city ?? '',
    state: p.state ?? '',
    postalCode: p.postalCode ?? '',
    contactEmail: p.contactEmail ?? '',
    contactPhone: p.contactPhone ?? '',
    academicYear: p.academicYear ?? suggestedAcademicYear(new Date()),
    timeZone: p.timeZone,
  }
}

/** Indian academic years usually start in June. */
export function suggestedAcademicYear(today: Date): string {
  const start = today.getMonth() >= 5 ? today.getFullYear() : today.getFullYear() - 1
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`
}

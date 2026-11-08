import { describe, expect, it } from 'vitest'
import { collegeProfileSchema, suggestedAcademicYear } from '@/features/college/schema'
import { defaultPolicySettings } from '@/test/fixtures'
import { describePolicy, policySettingsSchema } from './schema'

describe('policySettingsSchema', () => {
  it('accepts the backend defaults', () => {
    expect(policySettingsSchema.safeParse(defaultPolicySettings()).success).toBe(true)
  })

  it('enforces the same limits as the backend', () => {
    const s = defaultPolicySettings()
    s.workload.maxDutiesPerDay = 0
    s.staffing.studentsPerInvigilator = Number.NaN
    const result = policySettingsSchema.safeParse(s)

    expect(result.success).toBe(false)
    const paths = result.error!.issues.map((i) => i.path.join('.'))
    expect(paths).toEqual(expect.arrayContaining(['workload.maxDutiesPerDay', 'staffing.studentsPerInvigilator']))
  })

  it('allows empty optional caps', () => {
    const s = defaultPolicySettings()
    s.workload.maxDutiesPerPeriod = null
    s.staffing.practicalStudentsPerInvigilator = null
    expect(policySettingsSchema.safeParse(s).success).toBe(true)
  })
})

describe('describePolicy', () => {
  it('summarises rules in plain language', () => {
    const text = describePolicy(defaultPolicySettings()).flatMap((g) => g.items)

    expect(text).toContain('Invigilate own subject: not allowed')
    expect(text).toContain('At most 2 duties per day')
    expect(text).toContain('At most 2 consecutive sessions (never exceeded)')
    expect(text).toContain('Consecutive exam days: not limited')
  })
})

describe('college profile', () => {
  const valid = {
    name: 'GEC',
    affiliation: 'KTU',
    address: 'Road 1',
    city: 'Thrissur',
    state: 'Kerala',
    postalCode: '',
    contactEmail: '',
    contactPhone: '',
    academicYear: '2026-27',
    timeZone: 'Asia/Kolkata',
  }

  it('checks that the academic year runs over two consecutive years', () => {
    expect(collegeProfileSchema.safeParse(valid).success).toBe(true)
    expect(collegeProfileSchema.safeParse({ ...valid, academicYear: '2026-28' }).success).toBe(false)
    expect(collegeProfileSchema.safeParse({ ...valid, academicYear: '2099-00' }).success).toBe(true)
  })

  it('suggests the academic year starting in June', () => {
    expect(suggestedAcademicYear(new Date(2026, 10, 8))).toBe('2026-27')
    expect(suggestedAcademicYear(new Date(2027, 2, 1))).toBe('2026-27')
  })
})

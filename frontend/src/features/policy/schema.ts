import { z } from 'zod'

/**
 * Mirrors backend `PolicySettings` and its Bean Validation limits. The backend re-validates;
 * this exists for instant feedback.
 */
const int = (min: number, max: number) =>
  z
    .number({ error: 'Enter a number' })
    .int('Whole numbers only')
    .min(min, `At least ${min}`)
    .max(max, `At most ${max}`)

export const ruleModes = ['HARD', 'SOFT', 'OFF'] as const
export type RuleMode = (typeof ruleModes)[number]

const limit = z.object({ mode: z.enum(ruleModes), max: int(1, 20) })

export const policySettingsSchema = z.object({
  faculty: z.object({
    allowOwnSubject: z.boolean(),
    ownDepartmentExams: z.enum(['ALLOW', 'AVOID', 'FORBID']),
    visitingFacultyEligible: z.boolean(),
    seniorsGetFewerDuties: z.boolean(),
    preferSeniorsAsChief: z.boolean(),
    chiefInvigilator: z.enum(['NONE', 'PER_HALL']),
    chiefCountsTowardsStaffing: z.boolean(),
  }),
  workload: z.object({
    maxDutiesPerDay: int(1, 6),
    consecutiveSessions: limit,
    minRestMinutes: int(0, 720),
    consecutiveDays: limit,
    maxDutiesPerPeriod: int(1, 200).nullable(),
    equalDistributionWeight: int(0, 10),
    historicalWorkloadPercent: int(0, 100),
    avoidRepeatHallWeight: int(0, 10),
  }),
  staffing: z.object({
    studentsPerInvigilator: int(1, 500),
    practicalStudentsPerInvigilator: int(1, 500).nullable(),
    minInvigilatorsPerHall: int(1, 20),
  }),
  availability: z.object({
    allowFullDayUnavailability: z.boolean(),
    allowSessionUnavailability: z.boolean(),
    leaveRequiresHodApproval: z.boolean(),
    adminCanOverrideUnavailability: z.boolean(),
  }),
  swaps: z.object({
    swapsEnabled: z.boolean(),
    recipientMustAccept: z.boolean(),
    hodApprovalRequired: z.boolean(),
    adminCanReassignDirectly: z.boolean(),
  }),
})

export type PolicySettings = z.infer<typeof policySettingsSchema>
export type PolicySection = keyof PolicySettings

const MODE_TEXT: Record<RuleMode, string> = { HARD: 'never exceeded', SOFT: 'avoided when possible', OFF: 'not applied' }
const DEPT_TEXT = { ALLOW: 'allowed', AVOID: 'avoided when possible', FORBID: 'not allowed' } as const

/** Plain-language summary of a policy, for review screens. */
export function describePolicy(s: PolicySettings): { group: string; items: string[] }[] {
  const yes = (b: boolean) => (b ? 'Yes' : 'No')
  const w = s.workload
  return [
    {
      group: 'Faculty and subjects',
      items: [
        `Invigilate own subject: ${s.faculty.allowOwnSubject ? 'allowed' : 'not allowed'}`,
        `Invigilate own department's exams: ${DEPT_TEXT[s.faculty.ownDepartmentExams]}`,
        `Visiting faculty eligible: ${yes(s.faculty.visitingFacultyEligible)}`,
        `Seniors get fewer duties: ${yes(s.faculty.seniorsGetFewerDuties)}`,
        `Chief invigilator: ${s.faculty.chiefInvigilator === 'PER_HALL' ? 'one per hall' : 'not used'}`,
      ],
    },
    {
      group: 'Workload',
      items: [
        `At most ${w.maxDutiesPerDay} duties per day`,
        w.consecutiveSessions.mode === 'OFF'
          ? 'Consecutive sessions: not limited'
          : `At most ${w.consecutiveSessions.max} consecutive sessions (${MODE_TEXT[w.consecutiveSessions.mode]})`,
        w.minRestMinutes > 0 ? `At least ${w.minRestMinutes} minutes rest between duties` : 'No minimum rest period',
        w.consecutiveDays.mode === 'OFF'
          ? 'Consecutive exam days: not limited'
          : `At most ${w.consecutiveDays.max} consecutive days with duty (${MODE_TEXT[w.consecutiveDays.mode]})`,
        w.maxDutiesPerPeriod === null ? 'No cap per exam period' : `At most ${w.maxDutiesPerPeriod} duties per exam period`,
        `Equal distribution importance: ${w.equalDistributionWeight}/10; previous workload counts ${w.historicalWorkloadPercent}%`,
      ],
    },
    {
      group: 'Hall staffing',
      items: [
        `One invigilator per ${s.staffing.studentsPerInvigilator} students`,
        s.staffing.practicalStudentsPerInvigilator === null
          ? 'Practical exams use the same ratio'
          : `Practical exams: one per ${s.staffing.practicalStudentsPerInvigilator} students`,
        `At least ${s.staffing.minInvigilatorsPerHall} invigilator(s) per hall`,
      ],
    },
    {
      group: 'Availability and leave',
      items: [
        `Faculty can mark a full day unavailable: ${yes(s.availability.allowFullDayUnavailability)}`,
        `Faculty can mark a single session unavailable: ${yes(s.availability.allowSessionUnavailability)}`,
        `Leave needs HOD approval: ${yes(s.availability.leaveRequiresHodApproval)}`,
        `Exam cell may override unavailability: ${yes(s.availability.adminCanOverrideUnavailability)}`,
      ],
    },
    {
      group: 'Duty swaps',
      items: s.swaps.swapsEnabled
        ? [
            'Swaps allowed',
            `Recipient must accept: ${yes(s.swaps.recipientMustAccept)}`,
            `HOD approval required: ${yes(s.swaps.hodApprovalRequired)}`,
            `Exam cell may reassign directly: ${yes(s.swaps.adminCanReassignDirectly)}`,
          ]
        : ['Swaps not allowed', `Exam cell may reassign directly: ${yes(s.swaps.adminCanReassignDirectly)}`],
    },
  ]
}

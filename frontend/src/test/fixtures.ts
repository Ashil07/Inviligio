import type { CollegeDetails } from '@/features/college/api'
import type { PolicyView } from '@/features/policy/api'
import type { PolicySettings } from '@/features/policy/schema'

/** Same values as backend PolicySettings.defaults(). */
export function defaultPolicySettings(): PolicySettings {
  return {
    faculty: {
      allowOwnSubject: false,
      ownDepartmentExams: 'ALLOW',
      visitingFacultyEligible: true,
      seniorsGetFewerDuties: false,
      preferSeniorsAsChief: false,
      chiefInvigilator: 'NONE',
      chiefCountsTowardsStaffing: true,
    },
    workload: {
      maxDutiesPerDay: 2,
      consecutiveSessions: { mode: 'HARD', max: 2 },
      minRestMinutes: 0,
      consecutiveDays: { mode: 'OFF', max: 3 },
      maxDutiesPerPeriod: null,
      equalDistributionWeight: 8,
      historicalWorkloadPercent: 30,
      avoidRepeatHallWeight: 2,
    },
    staffing: { studentsPerInvigilator: 30, practicalStudentsPerInvigilator: null, minInvigilatorsPerHall: 1 },
    availability: {
      allowFullDayUnavailability: true,
      allowSessionUnavailability: true,
      leaveRequiresHodApproval: false,
      adminCanOverrideUnavailability: true,
    },
    swaps: { swapsEnabled: true, recipientMustAccept: true, hodApprovalRequired: false, adminCanReassignDirectly: true },
  }
}

export function defaultPolicyView(): PolicyView {
  return { version: 0, saved: false, settings: defaultPolicySettings(), createdAt: null, createdByName: null }
}

export function newCollege(overrides: Partial<CollegeDetails> = {}): CollegeDetails {
  return {
    id: 'c1',
    code: 'GEC',
    status: 'ONBOARDING',
    onboardingCompletedAt: null,
    profile: {
      name: 'Government Engineering College',
      affiliation: null,
      address: null,
      city: null,
      state: null,
      postalCode: null,
      contactEmail: null,
      contactPhone: null,
      academicYear: null,
      timeZone: 'Asia/Kolkata',
    },
    missingProfileFields: ['affiliation', 'address', 'city', 'state', 'academicYear'],
    ...overrides,
  }
}

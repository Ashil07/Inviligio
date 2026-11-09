import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export type Load = 'UNDER' | 'BALANCED' | 'OVER'

export interface PeriodOption {
  id: string
  name: string
  startDate: string
  endDate: string
  publishedVersion: number
}

/** `fairShare` is the even share for the person's duty weight; `load` is null for inactive faculty. */
export interface MemberRow {
  facultyId: string
  code: string
  name: string
  designation: string | null
  active: boolean
  dutyWeight: number
  duties: number
  chiefDuties: number
  fairShare: number
  load: Load | null
}

export interface DepartmentDuty {
  assignmentId: string
  date: string
  sessionLabel: string
  startTime: string
  endTime: string
  hallCode: string
  hallName: string
  facultyId: string
  facultyCode: string
  facultyName: string
  role: 'CHIEF' | 'INVIGILATOR'
}

/** The HOD's department in one published chart; period fields are null until something is published. */
export interface DepartmentOverview {
  department: { id: string; code: string; name: string }
  periods: PeriodOption[]
  periodId: string | null
  periodName: string | null
  chartVersion: number | null
  publishedAt: string | null
  departmentDuties: number
  collegeAverage: number
  departmentAverage: number
  faculty: MemberRow[]
  duties: DepartmentDuty[]
}

/** Without a period the backend picks the latest published one. */
export function useDepartmentOverview(periodId?: string) {
  return useQuery({
    queryKey: ['department', 'overview', periodId ?? 'latest'] as const,
    queryFn: async () => (await api.get<DepartmentOverview>('/department/overview', { params: { periodId } })).data,
    placeholderData: keepPreviousData,
  })
}

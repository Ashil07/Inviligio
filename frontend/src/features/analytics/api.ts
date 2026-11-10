import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export type Load = 'UNDER' | 'BALANCED' | 'OVER'

export interface PeriodOption {
  id: string
  name: string
  startDate: string
  publishedVersion: number
}

/** Per-person figures are over the college's active faculty. */
export interface FairnessSummary {
  positions: number
  filled: number
  unfilled: number
  activeFaculty: number
  duties: number
  mean: number
  min: number
  max: number
  stdDev: number
  over: number
  under: number
  withoutDuty: number
}

/** `load` is null for inactive faculty; `allPeriods` counts duties in every published chart. */
export interface FacultyRow {
  facultyId: string
  code: string
  name: string
  departmentCode: string
  active: boolean
  dutyWeight: number
  duties: number
  chiefDuties: number
  fairShare: number
  load: Load | null
  allPeriods: number
}

export interface DepartmentRow {
  code: string
  name: string
  faculty: number
  duties: number
  perPerson: number
  min: number
  max: number
  over: number
  under: number
}

export interface HistoryPoint {
  periodId: string
  name: string
  startDate: string
  positions: number
  filled: number
  mean: number
  stdDev: number
  min: number
  max: number
}

export interface UnfilledRow {
  date: string
  sessionLabel: string
  hallCode: string
  role: 'CHIEF' | 'INVIGILATOR'
  missing: number
  eliminated: Record<string, number>
}

/** Period fields and `summary` are null until a chart is published. */
export interface Fairness {
  periods: PeriodOption[]
  periodId: string | null
  periodName: string | null
  chartVersion: number | null
  summary: FairnessSummary | null
  faculty: FacultyRow[]
  departments: DepartmentRow[]
  history: HistoryPoint[]
  unfilled: UnfilledRow[]
}

/** Without a period the backend picks the latest published one. */
export function useFairness(periodId?: string) {
  return useQuery({
    queryKey: ['analytics', 'fairness', periodId ?? 'latest'] as const,
    queryFn: async () => (await api.get<Fairness>('/analytics/fairness', { params: { periodId } })).data,
    placeholderData: keepPreviousData,
  })
}

/** How many active people have each number of duties, from 0 to the highest. */
export function distribution(rows: FacultyRow[]): { duties: number; faculty: number }[] {
  const active = rows.filter((r) => r.active)
  const max = active.reduce((m, r) => Math.max(m, r.duties), 0)
  return Array.from({ length: max + 1 }, (_, duties) => ({ duties, faculty: active.filter((r) => r.duties === duties).length }))
}

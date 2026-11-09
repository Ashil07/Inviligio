import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AxiosError } from 'axios'
import { api } from '@/lib/api/client'

export type Role = 'CHIEF' | 'INVIGILATOR'

export interface Explanation {
  eligibleCandidates: number
  dutiesBefore: number
  penalties: string[]
}

export interface Duty {
  assignmentId: string
  facultyId: string
  facultyCode: string
  facultyName: string
  departmentCode: string
  role: Role
  source: 'AUTO' | 'MANUAL' | 'SWAP' | 'SUBSTITUTE'
  explanation: Explanation | null
}

export interface ChartHall {
  sessionHallId: string
  hallCode: string
  hallName: string
  required: number
  missing: number
  duties: Duty[]
}

export interface ChartSession {
  id: string
  date: string
  label: string
  startTime: string
  endTime: string
  halls: ChartHall[]
}

export interface Unfilled {
  sessionId: string
  date: string
  sessionLabel: string
  sessionHallId: string
  hallCode: string
  role: Role
  missing: number
  reason: { totalFaculty: number; eliminated: Record<string, number>; suggestions: string[] }
}

export interface WorkloadRow {
  facultyId: string
  code: string
  name: string
  departmentCode: string
  duties: number
  chiefDuties: number
  dutyWeight: number
}

export interface ChartStats {
  positions: number
  filled: number
  faculty: number
  minDuties: number
  maxDuties: number
  meanDuties: number
  stdDevDuties: number
  elapsedMillis: number
}

export interface DutyChart {
  id: string
  periodId: string
  periodName: string
  chartVersion: number
  status: 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED'
  generatedAt: string
  policyVersion: number | null
  policyChanged: boolean
  inputsChanged: boolean
  stats: ChartStats | null
  sessions: ChartSession[]
  unfilled: Unfilled[]
  workload: WorkloadRow[]
}

/** Plain-language labels for the engine's reasons (backend `Reason`). */
export const REASON_LABEL: Record<string, string> = {
  UNAVAILABLE: 'on leave or unavailable',
  VISITING_NOT_ELIGIBLE: 'visiting or guest faculty (not eligible)',
  EXCLUDED_HALL: 'personally excluded from this hall',
  EXCLUDED_DEPARTMENT: "personally excluded from this department's exams",
  OWN_SUBJECT: 'teach a subject examined here',
  OWN_DEPARTMENT: 'exam of their own department',
  NOT_CHIEF_ELIGIBLE: 'not eligible as chief',
  BUSY_IN_SESSION: 'already on duty at this time',
  MAX_PER_DAY: 'daily duty limit reached',
  MIN_REST: 'not enough rest between duties',
  MAX_CONSECUTIVE_SESSIONS: 'consecutive-session limit',
  MAX_CONSECUTIVE_DAYS: 'consecutive-day limit',
  MAX_PER_PERIOD: 'period duty limit reached',
}

const key = (periodId: string) => ['duty-chart', periodId] as const

/** The period's current chart, or null when none has been generated yet. */
export function useDutyChart(periodId: string) {
  return useQuery({
    queryKey: key(periodId),
    queryFn: async () => {
      try {
        return (await api.get<DutyChart>(`/exam-periods/${periodId}/duty-chart`)).data
      } catch (e) {
        if (e instanceof AxiosError && e.response?.status === 404 && (e.response.data as { code?: string })?.code === 'NO_CHART') {
          return null
        }
        throw e
      }
    },
  })
}

export function useGenerateChart(periodId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => (await api.post<DutyChart>(`/exam-periods/${periodId}/duty-chart/generate`)).data,
    onSuccess: (chart) => {
      queryClient.setQueryData(key(periodId), chart)
      void queryClient.invalidateQueries({ queryKey: ['schedule'] })
    },
  })
}

export function explain(d: Duty): string {
  if (!d.explanation) return 'Assigned manually.'
  const e = d.explanation
  const base = `Chosen from ${e.eligibleCandidates} eligible faculty; had ${e.dutiesBefore} duty(ies) in this period before.`
  return e.penalties.length ? `${base} Accepted: ${e.penalties.join('; ')}.` : base
}

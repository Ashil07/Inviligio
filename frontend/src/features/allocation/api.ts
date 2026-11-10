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
  version: number
  /** ABSENT: marked absent after the session, so not paid. */
  status: 'ASSIGNED' | 'COMPLETED' | 'ABSENT' | 'CANCELLED'
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
  active: boolean
}

/** A duty that breaks a strict rule now (e.g. leave approved after generation). */
export interface ChartIssue {
  assignmentId: string
  date: string
  sessionLabel: string
  hallCode: string
  facultyName: string
  reasons: string[]
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
  issues: ChartIssue[]
  /** Optimistic-lock version: changes with every edit. */
  version: number
  publishedAt: string | null
  publishedBy: string | null
  /** The period's currently published version, if any. */
  publishedVersion: number | null
  /** Draft only: swaps and substitutes made on the published version after this draft was started (they are not in it). */
  changesNotInDraft: number
}

export interface VersionSummary {
  chartVersion: number
  status: 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED'
  createdAt: string
  publishedAt: string | null
  publishedBy: string | null
  positions: number
  filled: number
}

export interface Suggestion {
  facultyId: string
  code: string
  name: string
  departmentCode: string
  duties: number
  warnings: string[]
}

/** The backend's verdict on a change, before it is made. */
export interface Validation {
  allowed: boolean
  blocking: { code: string; message: string }[]
  warnings: string[]
  alternatives: Suggestion[]
}

/** Fill an open position (sessionHallId + role) or give a duty to someone else (replacesAssignmentId). */
export interface ChangeRequest {
  sessionHallId: string
  role: Role
  facultyId?: string
  replacesAssignmentId?: string
}

export interface HistoryEntry {
  id: string
  occurredAt: string
  action: 'DUTY_CHART_GENERATED' | 'DUTY_ASSIGNED' | 'DUTY_REPLACED' | 'DUTY_REMOVED' | string
  actorName: string | null
  summary: string
  reason: string | null
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
  FACULTY_INACTIVE: 'no longer active',
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
      void queryClient.invalidateQueries({ queryKey: [...key(periodId), 'versions'] })
      void queryClient.invalidateQueries({ queryKey: [...key(periodId), 'history'] })
      void queryClient.invalidateQueries({ queryKey: ['schedule'] })
    },
  })
}

/** Checks a change without making it; re-runs whenever the request changes. */
export function useValidateChange(periodId: string, request: ChangeRequest | null) {
  return useQuery({
    queryKey: [...key(periodId), 'validate', request] as const,
    queryFn: async () => (await api.post<Validation>(`/exam-periods/${periodId}/duty-chart/assignments/validate`, request)).data,
    enabled: request !== null,
    staleTime: 0,
  })
}

export function useChartVersions(periodId: string) {
  return useQuery({
    queryKey: [...key(periodId), 'versions'] as const,
    queryFn: async () => (await api.get<VersionSummary[]>(`/exam-periods/${periodId}/duty-chart/versions`)).data,
    enabled: !!periodId,
  })
}

export function useChartVersion(periodId: string, chartVersion: number) {
  return useQuery({
    queryKey: [...key(periodId), 'versions', chartVersion] as const,
    queryFn: async () => (await api.get<DutyChart>(`/exam-periods/${periodId}/duty-chart/versions/${chartVersion}`)).data,
  })
}

export function useChartHistory(periodId: string, enabled = true) {
  return useQuery({
    queryKey: [...key(periodId), 'history'] as const,
    queryFn: async () => (await api.get<HistoryEntry[]>(`/exam-periods/${periodId}/duty-chart/history`)).data,
    enabled,
  })
}

/** Every chart edit returns the refreshed chart; validations and history are then out of date. */
function useChartEdit<T>(periodId: string, send: (input: T) => Promise<DutyChart>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: send,
    onSuccess: (chart) => {
      queryClient.setQueryData(key(periodId), chart)
      void queryClient.invalidateQueries({ queryKey: [...key(periodId), 'validate'] })
      void queryClient.invalidateQueries({ queryKey: [...key(periodId), 'history'] })
      void queryClient.invalidateQueries({ queryKey: [...key(periodId), 'versions'] })
      void queryClient.invalidateQueries({ queryKey: ['schedule'] })
    },
  })
}

/** Publishes the draft as reviewed; `version` is the draft's version on screen. */
export function usePublishChart(periodId: string) {
  return useChartEdit(periodId, async (version: number) =>
    (await api.post<DutyChart>(`/exam-periods/${periodId}/duty-chart/publish`, { version })).data)
}

/** Starts a new draft as a copy of the published chart. */
export function useNewDraft(periodId: string) {
  return useChartEdit(periodId, async () => (await api.post<DutyChart>(`/exam-periods/${periodId}/duty-chart/draft`)).data)
}

export function useAddDuty(periodId: string) {
  return useChartEdit(periodId, async (body: { sessionHallId: string; role: Role; facultyId: string; reason?: string }) =>
    (await api.post<DutyChart>(`/exam-periods/${periodId}/duty-chart/assignments`, body)).data)
}

export function useReplaceDuty(periodId: string) {
  return useChartEdit(periodId, async ({ assignmentId, ...body }: { assignmentId: string; facultyId: string; version: number; reason?: string }) =>
    (await api.put<DutyChart>(`/exam-periods/${periodId}/duty-chart/assignments/${assignmentId}`, body)).data)
}

export function useRemoveDuty(periodId: string) {
  return useChartEdit(periodId, async ({ assignmentId, version, reason }: { assignmentId: string; version: number; reason?: string }) =>
    (await api.delete<DutyChart>(`/exam-periods/${periodId}/duty-chart/assignments/${assignmentId}`, { params: { version, reason } })).data)
}

export interface SubstituteCandidate {
  facultyId: string
  code: string
  name: string
  departmentCode: string
  duties: number
  notes: string[]
}

/** Who could take over a duty of the published chart. `allowed`: the policy lets the exam cell reassign directly. */
export interface SubstituteOptions {
  duty: {
    assignmentId: string
    date: string
    sessionLabel: string
    startTime: string
    endTime: string
    hallCode: string
    hallName: string
    role: Role
    facultyId: string
    facultyName: string
    version: number
  }
  allowed: boolean
  candidates: SubstituteCandidate[]
  excluded: Unfilled['reason']
}

export function useSubstituteOptions(periodId: string, assignmentId: string) {
  return useQuery({
    queryKey: [...key(periodId), 'substitute', assignmentId] as const,
    queryFn: async () => (await api.get<SubstituteOptions>(`/exam-periods/${periodId}/duty-chart/assignments/${assignmentId}/substitute`)).data,
    staleTime: 0,
  })
}

/**
 * Gives a published duty to a substitute. The published chart may be shown on the version page
 * while a draft is current, so everything of the period is refetched instead of overwritten.
 */
export function useSubstitute(periodId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ assignmentId, ...body }: { assignmentId: string; facultyId: string; version: number; reason: string }) =>
      (await api.post<DutyChart>(`/exam-periods/${periodId}/duty-chart/assignments/${assignmentId}/substitute`, body)).data,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: key(periodId) })
    },
  })
}

/** Marks a duty of the published chart absent (not paid) or present again, after its session. */
export function useMarkAttendance(periodId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ assignmentId, ...body }: { assignmentId: string; absent: boolean; version: number; reason?: string }) =>
      (await api.put<DutyChart>(`/exam-periods/${periodId}/duty-chart/assignments/${assignmentId}/attendance`, body)).data,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: key(periodId) }),
        queryClient.invalidateQueries({ queryKey: ['remuneration'] }),
      ])
    },
  })
}

/** True once the session's end time has passed (browser clock; the server checks with the college's). */
export function sessionEnded(s: Pick<ChartSession, 'date' | 'endTime'>, now: Date = new Date()): boolean {
  return new Date(`${s.date}T${s.endTime}`) <= now
}

export function explain(d: Duty): string {
  if (d.source === 'SWAP') return 'Took this duty in an approved swap.'
  if (d.source === 'SUBSTITUTE') return 'Chosen by the examination cell as a substitute.'
  if (!d.explanation) return 'Assigned manually.'
  const e = d.explanation
  const base = `Chosen from ${e.eligibleCandidates} eligible faculty; had ${e.dutiesBefore} duty(ies) in this period before.`
  return e.penalties.length ? `${base} Accepted: ${e.penalties.join('; ')}.` : base
}

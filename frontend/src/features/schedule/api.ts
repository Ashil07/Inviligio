import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export type PeriodStatus = 'PLANNING' | 'ALLOCATING' | 'PUBLISHED' | 'CLOSED'
export type ExamType = 'THEORY' | 'PRACTICAL'

export interface Period {
  id: string
  name: string
  startDate: string
  endDate: string
  status: PeriodStatus
  sessionCount: number
  version: number
}

export interface SessionSummary {
  id: string
  date: string
  label: string
  startTime: string
  endTime: string
  examCount: number
  hallCount: number
  students: number
  positions: number
  warningCount: number
  version: number
}

export interface Ref {
  id: string
  code: string
  name: string
}

export interface ExamView {
  id: string
  subject: Ref
  department: Ref
  type: ExamType
  registeredStudents: number | null
  seatedStudents: number
  notes: string | null
  version: number
}

export interface Staffing {
  invigilators: number
  chief: number
  total: number
  basis: string
}

export interface SessionHallView {
  id: string
  hall: { id: string; code: string; name: string; capacity: number; minInvigilators: number; lab: boolean }
  seating: { examId: string; subjectCode: string; studentCount: number }[]
  totalStudents: number
  staffing: Staffing
  requiredOverride: number | null
  overrideReason: string | null
  version: number
}

export interface SessionDetail {
  id: string
  periodId: string
  periodName: string
  date: string
  label: string
  startTime: string
  endTime: string
  exams: ExamView[]
  halls: SessionHallView[]
  totalStudents: number
  totalPositions: number
  warnings: string[]
  version: number
}

export interface SessionInput {
  date: string
  label: string
  startTime: string
  endTime: string
}

export interface SessionHallInput {
  hallId?: string
  requiredOverride: number | null
  overrideReason: string | null
  seating: { examId: string; studentCount: number }[]
}

export const scheduleKeys = {
  periods: ['schedule', 'periods'] as const,
  period: (id: string) => ['schedule', 'period', id] as const,
  sessions: (periodId: string) => ['schedule', 'sessions', periodId] as const,
  session: (id: string) => ['schedule', 'session', id] as const,
}

/** "09:30:00" -> "09:30" */
export const hhmm = (t: string) => t.slice(0, 5)

export function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
}

export function usePeriods() {
  return useQuery({ queryKey: scheduleKeys.periods, queryFn: async () => (await api.get<Period[]>('/exam-periods')).data })
}

export function usePeriod(id: string) {
  return useQuery({ queryKey: scheduleKeys.period(id), queryFn: async () => (await api.get<Period>(`/exam-periods/${id}`)).data })
}

export function useSessions(periodId: string) {
  return useQuery({
    queryKey: scheduleKeys.sessions(periodId),
    queryFn: async () => (await api.get<SessionSummary[]>(`/exam-periods/${periodId}/sessions`)).data,
    enabled: !!periodId,
  })
}

export function useSession(id: string) {
  return useQuery({ queryKey: scheduleKeys.session(id), queryFn: async () => (await api.get<SessionDetail>(`/exam-sessions/${id}`)).data })
}

/** Every schedule change can affect period counts and session summaries. */
function useInvalidateSchedule() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: ['schedule'] })
}

export function useSavePeriod() {
  const invalidate = useInvalidateSchedule()
  return useMutation({
    mutationFn: async ({ id, ...body }: { id?: string; name: string; startDate: string; endDate: string; version?: number }) =>
      id ? (await api.put<Period>(`/exam-periods/${id}`, body)).data : (await api.post<Period>('/exam-periods', body)).data,
    onSuccess: invalidate,
  })
}

export function useDeletePeriod() {
  const invalidate = useInvalidateSchedule()
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/exam-periods/${id}`)
    },
    onSuccess: invalidate,
  })
}

export function useCreateSession(periodId: string) {
  const invalidate = useInvalidateSchedule()
  return useMutation({
    mutationFn: async (body: SessionInput) => (await api.post<SessionDetail>(`/exam-periods/${periodId}/sessions`, body)).data,
    onSuccess: invalidate,
  })
}

export function useGenerateSessions(periodId: string) {
  const invalidate = useInvalidateSchedule()
  return useMutation({
    mutationFn: async (body: { fromDate: string; toDate: string; skipDays: string[]; slots: Omit<SessionInput, 'date'>[] }) =>
      (await api.post<{ created: number; skipped: number }>(`/exam-periods/${periodId}/sessions/bulk`, body)).data,
    onSuccess: invalidate,
  })
}

/** Mutations on one session; each returns the refreshed session detail. */
export function useSessionMutations(sessionId: string) {
  const queryClient = useQueryClient()
  const onSuccess = (detail?: SessionDetail) => {
    if (detail) queryClient.setQueryData(scheduleKeys.session(sessionId), detail)
    void queryClient.invalidateQueries({ queryKey: ['schedule', 'sessions'] })
  }
  return {
    update: useMutation({
      mutationFn: async (body: SessionInput & { version: number }) => (await api.put<SessionDetail>(`/exam-sessions/${sessionId}`, body)).data,
      onSuccess,
    }),
    remove: useMutation({
      mutationFn: async () => {
        await api.delete(`/exam-sessions/${sessionId}`)
      },
      onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['schedule'] }),
    }),
    addExam: useMutation({
      mutationFn: async (body: { subjectId: string; type: ExamType; registeredStudents: number | null }) =>
        (await api.post<SessionDetail>(`/exam-sessions/${sessionId}/exams`, body)).data,
      onSuccess,
    }),
    updateExam: useMutation({
      mutationFn: async ({ id, ...body }: { id: string; subjectId: string; type: ExamType; registeredStudents: number | null; version: number }) =>
        (await api.put<SessionDetail>(`/exams/${id}`, body)).data,
      onSuccess,
    }),
    removeExam: useMutation({
      mutationFn: async (id: string) => (await api.delete<SessionDetail>(`/exams/${id}`)).data,
      onSuccess,
    }),
    addHall: useMutation({
      mutationFn: async (body: SessionHallInput) => (await api.post<SessionDetail>(`/exam-sessions/${sessionId}/halls`, body)).data,
      onSuccess,
    }),
    updateHall: useMutation({
      mutationFn: async ({ id, ...body }: SessionHallInput & { id: string; version: number }) =>
        (await api.put<SessionDetail>(`/session-halls/${id}`, body)).data,
      onSuccess,
    }),
    removeHall: useMutation({
      mutationFn: async (id: string) => (await api.delete<SessionDetail>(`/session-halls/${id}`)).data,
      onSuccess,
    }),
  }
}

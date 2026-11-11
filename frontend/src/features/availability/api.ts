import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export type AvailabilityType = 'LEAVE' | 'UNAVAILABLE'
export type AvailabilityStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

export interface Ref {
  id: string
  code: string
  name: string
}

export interface SessionRef {
  id: string
  date: string
  label: string
  startTime: string
  endTime: string
}

export interface AvailabilityRequest {
  id: string
  faculty: Ref
  departmentCode: string
  fromDate: string
  toDate: string
  session: SessionRef | null
  type: AvailabilityType
  reason: string | null
  status: AvailabilityStatus
  requestedByName: string | null
  reviewedByName: string | null
  reviewedAt: string | null
  reviewNote: string | null
  createdAt: string
}

export interface RequestBody {
  fromDate?: string
  toDate?: string
  sessionId?: string
  type: AvailabilityType
  reason?: string
}

export interface SessionAvailability {
  session: SessionRef
  unavailable: { faculty: Ref; type: AvailabilityType; reason: string | null; wholeDay: boolean }[]
  pending: Ref[]
}

export const TYPE_LABEL: Record<AvailabilityType, string> = { LEAVE: 'Leave', UNAVAILABLE: 'Unavailable' }

export const STATUS_STYLE: Record<AvailabilityStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-800',
  APPROVED: 'bg-emerald-50 text-emerald-700',
  REJECTED: 'bg-red-50 text-red-700',
  CANCELLED: 'bg-neutral-100 text-neutral-600',
}

const keys = {
  all: ['availability'] as const,
  mine: ['availability', 'mine'] as const,
  list: (status: string) => ['availability', 'list', status] as const,
  overview: (periodId: string) => ['availability', 'overview', periodId] as const,
}

export function useMyAvailability() {
  return useQuery({ queryKey: keys.mine, queryFn: async () => (await api.get<AvailabilityRequest[]>('/me/availability')).data })
}

export function useAvailabilityList(status: string) {
  return useQuery({
    queryKey: keys.list(status),
    queryFn: async () => (await api.get<AvailabilityRequest[]>('/availability', { params: { status: status || undefined } })).data,
  })
}

export function usePeriodAvailability(periodId: string) {
  return useQuery({
    queryKey: keys.overview(periodId),
    queryFn: async () => (await api.get<SessionAvailability[]>(`/exam-periods/${periodId}/availability`)).data,
    enabled: !!periodId,
  })
}

function useInvalidate() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: keys.all })
}

export function useRequestAvailability() {
  const onSuccess = useInvalidate()
  return useMutation({
    mutationFn: async (body: RequestBody) => (await api.post<AvailabilityRequest>('/me/availability', body)).data,
    onSuccess,
  })
}

export function useRecordAvailability() {
  const onSuccess = useInvalidate()
  return useMutation({
    mutationFn: async (body: RequestBody & { facultyId: string }) => (await api.post<AvailabilityRequest>('/availability', body)).data,
    onSuccess,
  })
}

export function useCancelAvailability() {
  const onSuccess = useInvalidate()
  return useMutation({
    mutationFn: async (id: string) => (await api.post<AvailabilityRequest>(`/availability/${id}/cancel`)).data,
    onSuccess,
  })
}

export function useReviewAvailability() {
  const onSuccess = useInvalidate()
  return useMutation({
    mutationFn: async ({ id, approve, note }: { id: string; approve: boolean; note?: string }) =>
      (await api.post<AvailabilityRequest>(`/availability/${id}/${approve ? 'approve' : 'reject'}`, { note })).data,
    onSuccess,
  })
}

/** "2 Mar 2027" / "2-4 Mar 2027" / "2 Mar 2027, FN 09:30-12:30" */
export function describeWhen(r: Pick<AvailabilityRequest, 'fromDate' | 'toDate' | 'session'>): string {
  const fmt = (d: string) => new Date(`${d}T00:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
  if (r.session) return `${fmt(r.fromDate)}, ${r.session.label} ${r.session.startTime.slice(0, 5)}-${r.session.endTime.slice(0, 5)}`
  return r.fromDate === r.toDate ? fmt(r.fromDate) : `${fmt(r.fromDate)} to ${fmt(r.toDate)}`
}

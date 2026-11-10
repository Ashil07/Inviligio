import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { formatDate, hhmm } from '@/features/schedule/api'
import { api } from '@/lib/api/client'

export type SwapStatus = 'PENDING_RECIPIENT' | 'PENDING_HOD' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'INVALIDATED'

export interface PersonRef {
  id: string
  code: string
  name: string
  departmentCode: string
}

export interface DutyRef {
  assignmentId: string
  date: string
  sessionLabel: string
  startTime: string
  endTime: string
  hallCode: string
  hallName: string
  role: 'CHIEF' | 'INVIGILATOR'
}

/** `targetDuty` is null for a handover. Send `version` back with every action. */
export interface Swap {
  id: string
  periodId: string
  periodName: string
  status: SwapStatus
  requester: PersonRef
  requesterDuty: DutyRef
  target: PersonRef
  targetDuty: DutyRef | null
  reason: string | null
  createdAt: string
  recipientRespondedAt: string | null
  recipientNote: string | null
  reviewedByName: string | null
  reviewedAt: string | null
  reviewNote: string | null
  invalidReason: string | null
  appliedAt: string | null
  version: number
}

export interface SwapInput {
  assignmentId: string
  targetFacultyId: string
  targetAssignmentId?: string | null
  reason?: string
}

export interface SwapCheck {
  allowed: boolean
  blocking: { code: string; message: string }[]
  warnings: string[]
}

/** `canTakeHandover` false: they cannot simply take the duty, but may trade one of theirs. */
export interface Colleague {
  faculty: PersonRef
  duties: number
  canTakeHandover: boolean
  warnings: string[]
  exchangeable: DutyRef[]
}

export interface SwapOptions {
  duty: DutyRef
  swapsEnabled: boolean
  colleagues: Colleague[]
}

export interface MySwaps {
  sent: Swap[]
  received: Swap[]
}

export const STATUS_LABEL: Record<SwapStatus, string> = {
  PENDING_RECIPIENT: 'Waiting for colleague',
  PENDING_HOD: 'Waiting for HOD',
  APPROVED: 'Done',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  INVALIDATED: 'No longer possible',
}

export const STATUS_STYLE: Record<SwapStatus, string> = {
  PENDING_RECIPIENT: 'bg-amber-100 text-amber-800',
  PENDING_HOD: 'bg-amber-100 text-amber-800',
  APPROVED: 'bg-emerald-50 text-emerald-700',
  REJECTED: 'bg-red-50 text-red-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  INVALIDATED: 'bg-slate-100 text-slate-600',
}

/** "Mon, Mar 1, 2027 · FN 09:30-12:30 · H101 (chief)" */
export function describeDuty(d: Pick<DutyRef, 'date' | 'sessionLabel' | 'startTime' | 'endTime' | 'hallCode' | 'role'>) {
  return `${formatDate(d.date)} · ${d.sessionLabel} ${hhmm(d.startTime)}-${hhmm(d.endTime)} · ${d.hallCode}${d.role === 'CHIEF' ? ' (chief)' : ''}`
}

const keys = {
  all: ['swaps'] as const,
  mine: ['swaps', 'mine'] as const,
  options: (assignmentId: string) => ['swaps', 'options', assignmentId] as const,
  check: (input: SwapInput | null) => ['swaps', 'check', input] as const,
  list: (status: string) => ['swaps', 'list', status] as const,
}

export function useMySwaps() {
  return useQuery({ queryKey: keys.mine, queryFn: async () => (await api.get<MySwaps>('/me/swap-requests')).data })
}

export function useSwapOptions(assignmentId: string) {
  return useQuery({
    queryKey: keys.options(assignmentId),
    queryFn: async () => (await api.get<SwapOptions>('/me/swap-options', { params: { assignmentId } })).data,
    enabled: !!assignmentId,
  })
}

/** Live check of a swap with the allocation rules, before asking for it. */
export function useCheckSwap(input: SwapInput | null) {
  return useQuery({
    queryKey: keys.check(input),
    queryFn: async () => (await api.post<SwapCheck>('/me/swap-requests/validate', input)).data,
    enabled: input !== null,
    staleTime: 0,
  })
}

/** HOD: their department's requests. Exam cell: everyone's. Empty status = all. */
export function useSwapList(status: string) {
  return useQuery({
    queryKey: keys.list(status),
    queryFn: async () => (await api.get<Swap[]>('/swap-requests', { params: { status: status || undefined } })).data,
  })
}

/** Swaps move duties, so the duty views refresh too. */
function useInvalidate() {
  const queryClient = useQueryClient()
  return async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: keys.all }),
      queryClient.invalidateQueries({ queryKey: ['me', 'duties'] }),
      queryClient.invalidateQueries({ queryKey: ['department'] }),
    ])
  }
}

export function useRequestSwap() {
  const onSuccess = useInvalidate()
  return useMutation({ mutationFn: async (input: SwapInput) => (await api.post<Swap>('/me/swap-requests', input)).data, onSuccess })
}

export type SwapAction = 'accept' | 'decline' | 'cancel' | 'approve' | 'reject'

export function useSwapAction() {
  const onSuccess = useInvalidate()
  return useMutation({
    mutationFn: async ({ swap, action, note }: { swap: Swap; action: SwapAction; note?: string }) =>
      (await api.post<Swap>(`/swap-requests/${swap.id}/${action}`, { version: swap.version, note })).data,
    onSuccess,
  })
}

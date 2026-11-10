import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export type Role = 'CHIEF' | 'INVIGILATOR'

/** A rate applies from `effectiveFrom` until the next rate of the same role. */
export interface Rate {
  id: string
  role: Role
  amount: number
  currency: string
  effectiveFrom: string
  createdByName: string | null
  createdAt: string
  version: number
}

export interface PeriodOption {
  id: string
  name: string
  startDate: string
  publishedVersion: number
}

/** `withoutRate`: completed duties with no rate on their date (not in `amount`). */
export interface FacultyPay {
  facultyId: string
  code: string
  name: string
  departmentCode: string
  completed: number
  chiefCompleted: number
  absent: number
  upcoming: number
  withoutRate: number
  amount: number
}

export interface DepartmentPay {
  code: string
  name: string
  faculty: number
  completed: number
  amount: number
}

export interface PaySummary {
  currency: string
  total: number
  completed: number
  absent: number
  upcoming: number
  withoutRate: number
}

/** Period fields and `summary` are null until a chart is published. */
export interface Pay {
  periods: PeriodOption[]
  periodId: string | null
  periodName: string | null
  chartVersion: number | null
  summary: PaySummary | null
  faculty: FacultyPay[]
  departments: DepartmentPay[]
}

const keys = {
  rates: ['remuneration', 'rates'] as const,
  pay: (periodId?: string) => ['remuneration', 'pay', periodId ?? 'latest'] as const,
}

export function useRates() {
  return useQuery({ queryKey: keys.rates, queryFn: async () => (await api.get<Rate[]>('/remuneration/rates')).data })
}

/** Without a period the backend picks the latest published one. */
export function usePay(periodId?: string) {
  return useQuery({
    queryKey: keys.pay(periodId),
    queryFn: async () => (await api.get<Pay>('/remuneration', { params: { periodId } })).data,
    placeholderData: keepPreviousData,
  })
}

/** Rates change the amounts, so pay is refetched too. */
function useInvalidate() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: ['remuneration'] })
}

export function useAddRate() {
  const onSuccess = useInvalidate()
  return useMutation({
    mutationFn: async (body: { role: Role; amount: string; effectiveFrom: string }) => (await api.post<Rate>('/remuneration/rates', body)).data,
    onSuccess,
  })
}

export function useDeleteRate() {
  const onSuccess = useInvalidate()
  return useMutation({
    mutationFn: async (rate: Rate) => {
      await api.delete(`/remuneration/rates/${rate.id}`, { params: { version: rate.version } })
    },
    onSuccess,
  })
}

/** "₹1,500.00" in the college's currency. */
export function money(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(amount)
  } catch {
    return `${currency} ${amount.toFixed(2)}`
  }
}

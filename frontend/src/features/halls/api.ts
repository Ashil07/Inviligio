import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export interface Hall {
  id: string
  code: string
  name: string
  building: string | null
  capacity: number
  minInvigilators: number
  lab: boolean
  active: boolean
  version: number
}

export interface HallInput {
  code: string
  name: string
  building: string
  capacity: number
  minInvigilators: number
  lab: boolean
  active: boolean
}

export const hallKeys = {
  all: ['halls'] as const,
  list: (includeInactive: boolean) => ['halls', { includeInactive }] as const,
}

export function useHalls(includeInactive = false) {
  return useQuery({
    queryKey: hallKeys.list(includeInactive),
    queryFn: async () => (await api.get<Hall[]>('/halls', { params: { includeInactive } })).data,
  })
}

export function useSaveHall() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, version, ...input }: HallInput & { id?: string; version?: number }) =>
      id ? (await api.put<Hall>(`/halls/${id}`, { ...input, version })).data : (await api.post<Hall>('/halls', input)).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: hallKeys.all }),
  })
}

export function useDeleteHall() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/halls/${id}`)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: hallKeys.all }),
  })
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export interface Department {
  id: string
  code: string
  name: string
  active: boolean
  version: number
}

export interface DepartmentInput {
  code: string
  name: string
  active: boolean
}

export const departmentKeys = {
  all: ['departments'] as const,
  list: (includeInactive: boolean) => ['departments', { includeInactive }] as const,
}

export function useDepartments(includeInactive = false) {
  return useQuery({
    queryKey: departmentKeys.list(includeInactive),
    queryFn: async () => (await api.get<Department[]>('/departments', { params: { includeInactive } })).data,
  })
}

export function useSaveDepartment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, version, ...input }: DepartmentInput & { id?: string; version?: number }) =>
      id
        ? (await api.put<Department>(`/departments/${id}`, { ...input, version })).data
        : (await api.post<Department>('/departments', input)).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: departmentKeys.all }),
  })
}

export function useDeleteDepartment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/departments/${id}`)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: departmentKeys.all }),
  })
}

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { Page } from '@/lib/api/page'

export interface Subject {
  id: string
  code: string
  name: string
  programme: string | null
  semester: number | null
  active: boolean
  department: { id: string; code: string; name: string }
  version: number
}

export interface SubjectInput {
  code: string
  name: string
  departmentId: string
  programme: string
  semester: number | null
  active: boolean
}

export interface SubjectFilters {
  q: string
  departmentId: string
  includeInactive: boolean
  page: number
  size: number
}

export const subjectKeys = {
  all: ['subjects'] as const,
  search: (f: SubjectFilters) => ['subjects', f] as const,
}

export function useSubjects(filters: SubjectFilters) {
  return useQuery({
    queryKey: subjectKeys.search(filters),
    queryFn: async () =>
      (
        await api.get<Page<Subject>>('/subjects', {
          params: {
            q: filters.q || undefined,
            departmentId: filters.departmentId || undefined,
            includeInactive: filters.includeInactive,
            page: filters.page,
            size: filters.size,
          },
        })
      ).data,
    placeholderData: keepPreviousData,
  })
}

export function useSaveSubject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, version, ...input }: SubjectInput & { id?: string; version?: number }) =>
      id
        ? (await api.put<Subject>(`/subjects/${id}`, { ...input, version })).data
        : (await api.post<Subject>('/subjects', input)).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: subjectKeys.all }),
  })
}

export function useDeleteSubject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/subjects/${id}`)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: subjectKeys.all }),
  })
}

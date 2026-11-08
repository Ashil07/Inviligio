import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Role } from '@/features/auth/api'
import { api } from '@/lib/api/client'
import type { Page } from '@/lib/api/page'

export type EmploymentType = 'REGULAR' | 'VISITING' | 'GUEST' | 'NON_TEACHING'
export type RestrictionType = 'EXCLUDE_HALL' | 'EXCLUDE_DEPARTMENT_EXAMS' | 'NOT_CHIEF' | 'MAX_DUTIES_PER_DAY' | 'MAX_DUTIES_PER_PERIOD'

export const EMPLOYMENT_LABEL: Record<EmploymentType, string> = {
  REGULAR: 'Regular',
  VISITING: 'Visiting',
  GUEST: 'Guest',
  NON_TEACHING: 'Non-teaching',
}

export interface Ref {
  id: string
  code: string
  name: string
}

export interface FacultySummary {
  id: string
  employeeCode: string
  fullName: string
  email: string | null
  department: Ref
  designation: string | null
  employmentType: EmploymentType
  chiefEligible: boolean
  dutyWeight: number
  active: boolean
  subjectCount: number
  version: number
}

export interface Restriction {
  id: string
  type: RestrictionType
  hall: Ref | null
  department: Ref | null
  maxValue: number | null
  reason: string | null
}

export interface FacultyDetail {
  id: string
  employeeCode: string
  fullName: string
  email: string | null
  phone: string | null
  department: Ref
  designation: string | null
  employmentType: EmploymentType
  joinedOn: string | null
  chiefEligible: boolean
  dutyWeight: number
  active: boolean
  subjects: Ref[]
  restrictions: Restriction[]
  version: number
}

export interface FacultyInput {
  employeeCode: string
  fullName: string
  departmentId: string
  email: string
  phone: string
  designation: string
  employmentType: EmploymentType
  joinedOn: string | null
  chiefEligible: boolean
  dutyWeight: number
  active: boolean
  subjectIds: string[]
}

export interface FacultyFilters {
  q: string
  departmentId: string
  employmentType: string
  includeInactive: boolean
  page: number
  size: number
}

export type AccessStatus = 'NONE' | 'INVITED' | 'ACTIVE' | 'DISABLED'

export interface AccessDetail {
  status: AccessStatus
  roles: Role[]
  loginEmail: string | null
  invitation: { email: string; roles: Role[]; createdAt: string; expiresAt: string } | null
  currentUser: boolean
}

export interface IssuedInvitation {
  token: string
  expiresAt: string
  access: AccessDetail
}

export const facultyKeys = {
  all: ['faculty'] as const,
  search: (f: FacultyFilters) => ['faculty', 'search', f] as const,
  detail: (id: string) => ['faculty', 'detail', id] as const,
  access: (id: string) => ['faculty', 'access', id] as const,
}

export function useFacultySearch(filters: FacultyFilters) {
  return useQuery({
    queryKey: facultyKeys.search(filters),
    queryFn: async () =>
      (
        await api.get<Page<FacultySummary>>('/faculty', {
          params: {
            q: filters.q || undefined,
            departmentId: filters.departmentId || undefined,
            employmentType: filters.employmentType || undefined,
            includeInactive: filters.includeInactive,
            page: filters.page,
            size: filters.size,
          },
        })
      ).data,
    placeholderData: keepPreviousData,
  })
}

export function useFacultyMember(id: string | undefined) {
  return useQuery({
    queryKey: facultyKeys.detail(id ?? ''),
    queryFn: async () => (await api.get<FacultyDetail>(`/faculty/${id}`)).data,
    enabled: !!id,
  })
}

export function useSaveFaculty() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, version, ...input }: FacultyInput & { id?: string; version?: number }) =>
      id
        ? (await api.put<FacultyDetail>(`/faculty/${id}`, { ...input, version })).data
        : (await api.post<FacultyDetail>('/faculty', input)).data,
    onSuccess: (saved) => {
      queryClient.setQueryData(facultyKeys.detail(saved.id), saved)
      void queryClient.invalidateQueries({ queryKey: ['faculty', 'search'] })
    },
  })
}

export function useDeleteFaculty() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/faculty/${id}`)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: facultyKeys.all }),
  })
}

export function useAddRestriction(facultyId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: { type: RestrictionType; hallId?: string; departmentId?: string; maxValue?: number; reason?: string }) =>
      (await api.post<Restriction>(`/faculty/${facultyId}/restrictions`, body)).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: facultyKeys.detail(facultyId) }),
  })
}

export function useRemoveRestriction(facultyId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (restrictionId: string) => {
      await api.delete(`/faculty/${facultyId}/restrictions/${restrictionId}`)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: facultyKeys.detail(facultyId) }),
  })
}

export function useAccess(facultyId: string) {
  return useQuery({
    queryKey: facultyKeys.access(facultyId),
    queryFn: async () => (await api.get<AccessDetail>(`/faculty/${facultyId}/access`)).data,
  })
}

export function useInvite(facultyId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (roles: Role[]) =>
      (await api.post<IssuedInvitation>(`/faculty/${facultyId}/access/invitation`, { roles })).data,
    onSuccess: (issued) => queryClient.setQueryData(facultyKeys.access(facultyId), issued.access),
  })
}

export function useRevokeInvite(facultyId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => (await api.delete<AccessDetail>(`/faculty/${facultyId}/access/invitation`)).data,
    onSuccess: (access) => queryClient.setQueryData(facultyKeys.access(facultyId), access),
  })
}

export function useUpdateAccess(facultyId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: { roles: Role[]; active: boolean }) =>
      (await api.put<AccessDetail>(`/faculty/${facultyId}/access`, body)).data,
    onSuccess: (access) => queryClient.setQueryData(facultyKeys.access(facultyId), access),
  })
}

/** The link the exam cell shares with the faculty member. */
export function invitationLink(token: string): string {
  return `${window.location.origin}/accept-invite?token=${encodeURIComponent(token)}`
}

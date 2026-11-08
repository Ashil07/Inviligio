import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { CollegeStatus, SessionInfo } from '@/features/auth/api'
import { sessionStore } from '@/features/auth/sessionStore'
import type { CollegeProfile } from './schema'

export interface CollegeDetails {
  id: string
  code: string
  status: CollegeStatus
  onboardingCompletedAt: string | null
  profile: {
    name: string
    affiliation: string | null
    address: string | null
    city: string | null
    state: string | null
    postalCode: string | null
    contactEmail: string | null
    contactPhone: string | null
    academicYear: string | null
    timeZone: string
  }
  missingProfileFields: string[]
}

export const collegeKeys = { current: ['college', 'current'] as const }

export function useCollege() {
  return useQuery({
    queryKey: collegeKeys.current,
    queryFn: async () => (await api.get<CollegeDetails>('/college')).data,
  })
}

/** College name/status also live in the signed-in session; keep it in step after changes. */
async function refreshSessionInfo() {
  const { data } = await api.get<SessionInfo>('/auth/me')
  sessionStore.updateSession(data)
}

export function useUpdateCollege() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (profile: CollegeProfile) => (await api.put<CollegeDetails>('/college', profile)).data,
    onSuccess: async (details) => {
      queryClient.setQueryData(collegeKeys.current, details)
      await refreshSessionInfo()
    },
  })
}

export function useCompleteOnboarding() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => (await api.post<CollegeDetails>('/college/complete-onboarding')).data,
    onSuccess: async (details) => {
      queryClient.setQueryData(collegeKeys.current, details)
      await refreshSessionInfo()
    },
  })
}

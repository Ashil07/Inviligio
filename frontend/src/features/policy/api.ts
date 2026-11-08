import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { PolicySettings } from './schema'

/** version 0 + saved=false means nothing is saved yet and `settings` are the suggested defaults. */
export interface PolicyView {
  version: number
  saved: boolean
  settings: PolicySettings
  createdAt: string | null
  createdByName: string | null
}

export interface PolicyVersionSummary {
  version: number
  createdAt: string
  createdByName: string | null
}

export const policyKeys = {
  current: ['policy', 'current'] as const,
  versions: ['policy', 'versions'] as const,
  version: (v: number) => ['policy', 'versions', v] as const,
}

export function usePolicy() {
  return useQuery({
    queryKey: policyKeys.current,
    queryFn: async () => (await api.get<PolicyView>('/policy')).data,
  })
}

export function usePolicyVersions() {
  return useQuery({
    queryKey: policyKeys.versions,
    queryFn: async () => (await api.get<PolicyVersionSummary[]>('/policy/versions')).data,
  })
}

export function usePolicyVersion(version: number) {
  return useQuery({
    queryKey: policyKeys.version(version),
    queryFn: async () => (await api.get<PolicyView>(`/policy/versions/${version}`)).data,
  })
}

export function useSavePolicy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: { basedOnVersion: number; settings: PolicySettings }) =>
      (await api.put<PolicyView>('/policy', body)).data,
    onSuccess: (saved) => {
      queryClient.setQueryData(policyKeys.current, saved)
      void queryClient.invalidateQueries({ queryKey: policyKeys.versions })
    },
  })
}

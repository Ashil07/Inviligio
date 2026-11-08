import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export interface SystemStatus {
  application: string
  serverTime: string
  database: { status: 'UP' | 'DOWN'; schemaVersion: string | null }
}

export async function fetchSystemStatus(): Promise<SystemStatus> {
  const { data } = await api.get<SystemStatus>('/system/status')
  return data
}

export function useSystemStatus() {
  return useQuery({ queryKey: ['system', 'status'], queryFn: fetchSystemStatus, retry: false })
}

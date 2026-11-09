import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export interface MyDuty {
  assignmentId: string
  periodId: string
  periodName: string
  date: string
  sessionLabel: string
  startTime: string
  endTime: string
  hallCode: string
  hallName: string
  building: string | null
  role: 'CHIEF' | 'INVIGILATOR'
  status: 'ASSIGNED' | 'COMPLETED' | 'ABSENT' | 'CANCELLED'
  colleagues: string[]
}

/** The signed-in faculty member's published duties; `today` is the college's local date. */
export interface MyDuties {
  timeZone: string
  today: string
  chiefDuties: number
  upcoming: MyDuty[]
  past: MyDuty[]
}

export function useMyDuties() {
  return useQuery({
    queryKey: ['me', 'duties'] as const,
    queryFn: async () => (await api.get<MyDuties>('/me/duties')).data,
  })
}

/** Downloads all published duties as an .ics file for calendar apps. */
export function useDownloadCalendar() {
  return useMutation({
    mutationFn: async () => {
      const response = await api.get<Blob>('/me/duties.ics', { responseType: 'blob' })
      const url = URL.createObjectURL(response.data)
      const link = document.createElement('a')
      link.href = url
      link.download = 'invigilation-duties.ics'
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
    },
  })
}

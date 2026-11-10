import { useMutation } from '@tanstack/react-query'
import { api } from '@/lib/api/client'

export type ReportType = 'duty-chart' | 'workload' | 'fairness' | 'remuneration'
export type ReportFormat = 'pdf' | 'xlsx' | 'csv'
export type GroupBy = 'date' | 'session' | 'hall' | 'faculty' | 'department'

export interface ReportRequest {
  periodId: string
  type: ReportType
  format: ReportFormat
  /** Duty chart only. */
  groupBy?: GroupBy
  /** Duty chart only: a chart version instead of the published one. */
  version?: number
  department?: string
  from?: string
  to?: string
  session?: string
  hall?: string
}

/** The file name the server chose (Content-Disposition), or a fallback. */
export function filenameFrom(header: string | undefined, fallback: string): string {
  const match = header?.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i)
  return match?.[1] ? decodeURIComponent(match[1]) : fallback
}

/** Only the parameters that apply to the report and are filled in. */
export function reportParams(r: ReportRequest): Record<string, string | number> {
  const params: Record<string, string | number> = { format: r.format }
  const add = (name: string, value: string | number | undefined) => {
    if (value !== undefined && value !== '') params[name] = value
  }
  if (r.type === 'duty-chart') {
    add('groupBy', r.groupBy)
    add('version', r.version)
    add('from', r.from)
    add('to', r.to)
    add('session', r.session)
    add('hall', r.hall)
  }
  if (r.type !== 'fairness') add('department', r.department)
  return params
}

/** Downloads a report file: fetched with the session's token, then saved by the browser. */
export function useDownloadReport() {
  return useMutation({
    mutationFn: async (r: ReportRequest) => {
      const response = await api
        .get<Blob>(`/exam-periods/${r.periodId}/reports/${r.type}`, { params: reportParams(r), responseType: 'blob' })
        .catch(async (error: unknown) => {
          // With responseType 'blob' an error's problem details arrive as a Blob; read them so the message shows.
          const res = (error as { response?: { data?: unknown } }).response
          if (res?.data instanceof Blob) {
            try {
              res.data = JSON.parse(await res.data.text())
            } catch {
              // not JSON: keep the generic message
            }
          }
          throw error
        })
      const name = filenameFrom(response.headers['content-disposition'] as string | undefined, `${r.type}.${r.format}`)
      const url = URL.createObjectURL(response.data)
      const link = document.createElement('a')
      link.href = url
      link.download = name
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      return name
    },
  })
}

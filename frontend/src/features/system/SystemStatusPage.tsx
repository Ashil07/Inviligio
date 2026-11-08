import { CheckCircle2, Database, Loader2, Server, XCircle } from 'lucide-react'
import type { ReactNode } from 'react'
import { toApiProblem } from '@/lib/api/client'
import { useSystemStatus } from './api'

/**
 * Developer setup check: proves the browser -> Vite proxy -> Spring Boot -> PostgreSQL path works.
 * Replaced as the home route once authentication lands (Developer 1, milestone M1).
 */
export function SystemStatusPage() {
  const { data, error, isPending, refetch, isFetching } = useSystemStatus()
  const problem = error ? toApiProblem(error) : null

  return (
    <main className="mx-auto max-w-2xl px-4 py-12">
      <h1 className="text-3xl font-semibold tracking-tight text-slate-900">Invigilo</h1>
      <p className="mt-2 text-slate-600">Smart examination invigilation duty allocator</p>

      <section className="mt-10 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-medium text-slate-900">System status</h2>
          <button
            type="button"
            onClick={() => void refetch()}
            disabled={isFetching}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            {isFetching ? 'Checking...' : 'Check again'}
          </button>
        </div>

        <ul className="mt-6 space-y-4">
          <StatusRow
            icon={<Server className="size-5" aria-hidden />}
            label="API server"
            state={isPending ? 'pending' : problem ? 'down' : 'up'}
            detail={problem ? (problem.detail ?? `HTTP ${problem.status}`) : data?.application}
          />
          <StatusRow
            icon={<Database className="size-5" aria-hidden />}
            label="Database"
            state={isPending ? 'pending' : data?.database.status === 'UP' ? 'up' : 'down'}
            detail={
              data?.database.status === 'UP'
                ? `Schema version ${data.database.schemaVersion ?? 'none'}`
                : data
                  ? 'API is running but cannot reach PostgreSQL'
                  : undefined
            }
          />
        </ul>
      </section>
    </main>
  )
}

function StatusRow(props: {
  icon: ReactNode
  label: string
  state: 'pending' | 'up' | 'down'
  detail?: string
}) {
  return (
    <li className="flex items-start gap-3">
      <span className="mt-0.5 text-slate-500">{props.icon}</span>
      <div className="flex-1">
        <p className="font-medium text-slate-800">{props.label}</p>
        {props.detail && <p className="text-sm text-slate-500">{props.detail}</p>}
      </div>
      {props.state === 'pending' && <Loader2 className="size-5 animate-spin text-slate-400" aria-label="Checking" />}
      {props.state === 'up' && <CheckCircle2 className="size-5 text-emerald-600" aria-label="Up" />}
      {props.state === 'down' && <XCircle className="size-5 text-red-600" aria-label="Down" />}
    </li>
  )
}

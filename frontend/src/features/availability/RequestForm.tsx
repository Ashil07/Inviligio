import { useState } from 'react'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { SelectField } from '@/components/ui/fields'
import { formatDate, hhmm, usePeriods, useSessions } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import type { AvailabilityType, RequestBody } from './api'

type Mode = 'days' | 'session'

/**
 * Whole days (date range) or one examination session. Which modes are offered depends on the
 * caller: faculty get what the college policy allows, the exam cell gets both.
 */
export function RequestForm({
  allowDays,
  allowSession,
  submitLabel,
  onSubmit,
  busy,
}: {
  allowDays: boolean
  allowSession: boolean
  submitLabel: string
  onSubmit: (body: RequestBody) => Promise<void>
  busy: boolean
}) {
  const [mode, setMode] = useState<Mode>(allowDays ? 'days' : 'session')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [periodId, setPeriodId] = useState('')
  const [sessionId, setSessionId] = useState('')
  const [type, setType] = useState<AvailabilityType>('LEAVE')
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const periods = usePeriods()
  const sessions = useSessions(periodId)

  async function submit(ev: React.FormEvent) {
    ev.preventDefault()
    setProblem(null)
    if (mode === 'days' && (!fromDate || !toDate)) return setProblem('Choose the first and last day.')
    if (mode === 'session' && !sessionId) return setProblem('Choose a session.')
    try {
      await onSubmit(
        mode === 'days'
          ? { fromDate, toDate, type, reason: reason || undefined }
          : { sessionId, type, reason: reason || undefined },
      )
      setFromDate('')
      setToDate('')
      setSessionId('')
      setReason('')
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not save the request.')
    }
  }

  if (!allowDays && !allowSession) {
    return <p className="text-sm text-neutral-600">Your college does not accept availability requests online. Contact the examination cell.</p>
  }

  return (
    <form onSubmit={(ev) => void submit(ev)} noValidate className="space-y-4">
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {allowDays && allowSession && (
        <fieldset className="flex gap-6 text-sm">
          <legend className="sr-only">Unavailable for</legend>
          <label className="flex items-center gap-2">
            <input type="radio" name="mode" checked={mode === 'days'} onChange={() => setMode('days')} className="accent-primary-600" />
            Whole days
          </label>
          <label className="flex items-center gap-2">
            <input type="radio" name="mode" checked={mode === 'session'} onChange={() => setMode('session')} className="accent-primary-600" />
            One session
          </label>
        </fieldset>
      )}
      {mode === 'days' ? (
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="First day"
            type="date"
            value={fromDate}
            onChange={(e) => {
              setFromDate(e.target.value)
              if (!toDate || toDate < e.target.value) setToDate(e.target.value)
            }}
          />
          <TextField label="Last day" type="date" min={fromDate} value={toDate} onChange={(e) => setToDate(e.target.value)} />
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          <SelectField
            label="Examination period"
            value={periodId}
            onChange={(e) => {
              setPeriodId(e.target.value)
              setSessionId('')
            }}
            options={[{ value: '', label: 'Choose...' }, ...(periods.data ?? []).map((p) => ({ value: p.id, label: p.name }))]}
          />
          <SelectField
            label="Session"
            value={sessionId}
            disabled={!periodId}
            onChange={(e) => setSessionId(e.target.value)}
            options={[
              { value: '', label: 'Choose...' },
              ...(sessions.data ?? []).map((s) => ({ value: s.id, label: `${formatDate(s.date)} ${s.label} ${hhmm(s.startTime)}-${hhmm(s.endTime)}` })),
            ]}
          />
        </div>
      )}
      <div className="grid gap-4 sm:grid-cols-3">
        <SelectField
          label="Type"
          value={type}
          onChange={(e) => setType(e.target.value as AvailabilityType)}
          options={[{ value: 'LEAVE', label: 'Leave' }, { value: 'UNAVAILABLE', label: 'Unavailable (other reason)' }]}
        />
        <div className="sm:col-span-2">
          <TextField label="Reason" hint="Optional" value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
      </div>
      <Button type="submit" loading={busy}>{submitLabel}</Button>
    </form>
  )
}

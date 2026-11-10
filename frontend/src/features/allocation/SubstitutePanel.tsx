import { zodResolver } from '@hookform/resolvers/zod'
import { AlertTriangle } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { EditPanel } from '@/components/ui/layout'
import { formatDate, hhmm } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import { REASON_LABEL, useSubstitute, useSubstituteOptions, type ChartHall, type ChartSession, type Duty } from './api'

export interface SubstituteTarget {
  session: ChartSession
  hall: ChartHall
  duty: Duty
}

const schema = z.object({
  facultyId: z.string().min(1, 'Choose the substitute'),
  reason: z.string().trim().min(1, 'Say why, e.g. "absent: illness"').max(500, 'Keep the reason under 500 characters'),
})
type Values = z.infer<typeof schema>

/** Exam cell: pick a substitute for a duty of the published chart, from the people the rules allow, best first. */
export function SubstitutePanel({ periodId, target, onDone }: { periodId: string; target: SubstituteTarget; onDone: (message: string) => void }) {
  const { session, hall, duty } = target
  const panel = useRef<HTMLDivElement>(null)
  // Braces matter: newer browsers return a Promise from scrollIntoView, which React would treat as a cleanup.
  useEffect(() => {
    panel.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
  }, [duty.assignmentId])
  const options = useSubstituteOptions(periodId, duty.assignmentId)
  const save = useSubstitute(periodId)
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { facultyId: '', reason: '' } })
  const facultyId = useWatch({ control: form.control, name: 'facultyId' })
  const where = `${duty.role === 'CHIEF' ? 'chief invigilator' : 'invigilator'} in ${hall.hallCode}, ${formatDate(session.date)} ${session.label} ${hhmm(session.startTime)}-${hhmm(session.endTime)}`

  async function onSubmit(values: Values) {
    if (!options.data) return
    const chosen = options.data.candidates.find((c) => c.facultyId === values.facultyId)
    // A failure is shown from save.error; catching keeps it from becoming an unhandled rejection.
    const saved = await save
      .mutateAsync({ assignmentId: duty.assignmentId, facultyId: values.facultyId, version: options.data.duty.version, reason: values.reason })
      .catch(() => null)
    if (!saved) return
    onDone(`${chosen?.name ?? 'The substitute'} now has the duty of ${duty.facultyName} (${where}).`)
  }

  const excluded = options.data?.excluded
  return (
    <div ref={panel}>
      <EditPanel title={`Substitute for ${duty.facultyName}`} onClose={() => onDone('')}>
        <p className="text-sm text-slate-600">
          {duty.facultyName} cannot do the duty as {where}. Choose who takes it over; only people who keep every strict rule are listed, best
          first. The change applies at once and the substitute sees it on their dashboard.
        </p>
        {options.isPending && <p className="mt-3 text-sm text-slate-500">Finding substitutes...</p>}
        {options.error && (
          <div className="mt-3">
            <ErrorAlert>{toApiProblem(options.error).detail ?? 'Could not load substitutes.'}</ErrorAlert>
          </div>
        )}
        {options.data && !options.data.allowed && (
          <p className="mt-3 flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
            <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden />
            The college policy does not let the examination cell reassign published duties directly. Change it under Policy, or start a new
            version of the chart.
          </p>
        )}
        {options.data && (
          <form className="mt-4 space-y-4" onSubmit={form.handleSubmit(onSubmit)} noValidate>
            {options.data.candidates.length === 0 ? (
              <p className="text-sm text-red-700">Nobody can take this duty without breaking a strict rule.</p>
            ) : (
              <fieldset>
                <legend className="text-sm font-medium text-slate-700">Substitute</legend>
                <ul className="mt-2 divide-y divide-slate-100 rounded-md border border-slate-200">
                  {options.data.candidates.map((c, i) => (
                    <li key={c.facultyId}>
                      <label className={`flex cursor-pointer items-start gap-3 px-3 py-2 text-sm ${facultyId === c.facultyId ? 'bg-indigo-50' : ''}`}>
                        <input type="radio" value={c.facultyId} className="mt-1" {...form.register('facultyId')} />
                        <span>
                          <span className="font-medium text-slate-900">{c.name}</span>{' '}
                          <span className="text-slate-500">
                            ({c.code}, {c.departmentCode}) · {c.duties} {c.duties === 1 ? 'duty' : 'duties'} in this period
                            {i === 0 && ' · best fit'}
                          </span>
                          {c.notes.length > 0 && <span className="block text-xs text-amber-800">{c.notes.join('; ')}</span>}
                        </span>
                      </label>
                    </li>
                  ))}
                </ul>
                {form.formState.errors.facultyId && <p className="mt-1 text-sm text-red-600">{form.formState.errors.facultyId.message}</p>}
              </fieldset>
            )}
            {excluded && (
              <p className="text-xs text-slate-500">
                Of {excluded.totalFaculty} faculty:{' '}
                {Object.entries(excluded.eliminated).map(([r, n]) => `${n} ${REASON_LABEL[r] ?? r}`).join(', ') || 'none excluded'}.
              </p>
            )}
            <TextField label="Reason" placeholder="absent: illness" error={form.formState.errors.reason?.message} {...form.register('reason')} />
            {save.error && <ErrorAlert>{toApiProblem(save.error).detail ?? 'Could not save the substitute.'}</ErrorAlert>}
            <div className="flex gap-2">
              <Button type="submit" loading={save.isPending} disabled={!options.data.allowed || options.data.candidates.length === 0}>
                Give the duty to the substitute
              </Button>
              <Button type="button" variant="secondary" onClick={() => onDone('')}>
                Cancel
              </Button>
            </div>
          </form>
        )}
      </EditPanel>
    </div>
  )
}

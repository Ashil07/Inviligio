import { zodResolver } from '@hookform/resolvers/zod'
import { AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { SelectField } from '@/components/ui/fields'
import { EditPanel } from '@/components/ui/layout'
import { formatDate, hhmm } from '@/features/schedule/api'
import { toApiProblem } from '@/lib/api/client'
import {
  useAddDuty,
  useRemoveDuty,
  useReplaceDuty,
  useValidateChange,
  type ChangeRequest,
  type ChartHall,
  type ChartSession,
  type Duty,
  type DutyChart,
  type Role,
} from './api'

/** What the admin is changing: a duty to give to someone else, an open position to fill, or a duty to remove. */
export type EditTarget =
  | { kind: 'replace'; session: ChartSession; hall: ChartHall; duty: Duty }
  | { kind: 'fill'; session: ChartSession; hall: ChartHall; role: Role }
  | { kind: 'remove'; session: ChartSession; hall: ChartHall; duty: Duty }

const reasonSchema = z.string().trim().max(500, 'Keep the reason under 500 characters')

const changeSchema = z.object({
  facultyId: z.string().min(1, 'Choose a faculty member'),
  reason: reasonSchema,
})
type ChangeValues = z.infer<typeof changeSchema>

function where(t: EditTarget) {
  const role = t.kind === 'fill' ? t.role : t.duty.role
  return `${role === 'CHIEF' ? 'chief invigilator' : 'invigilator'} in ${t.hall.hallCode}, ${formatDate(t.session.date)} ${t.session.label} ${hhmm(t.session.startTime)}-${hhmm(t.session.endTime)}`
}

/** The panel for one change. Every choice is checked by the server with the allocation rules before it can be saved. */
export function ChartEditor({ chart, target, onDone }: { chart: DutyChart; target: EditTarget; onDone: () => void }) {
  const panel = useRef<HTMLDivElement>(null)
  // Braces matter: newer browsers return a Promise from scrollIntoView, which React would treat as a cleanup.
  useEffect(() => {
    panel.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
  }, [target])
  return (
    <div ref={panel}>
      {target.kind === 'remove' ? (
        <RemoveDuty periodId={chart.periodId} target={target} onDone={onDone} />
      ) : (
        <ChangeDuty chart={chart} target={target} onDone={onDone} />
      )}
    </div>
  )
}

function ChangeDuty({ chart, target, onDone }: { chart: DutyChart; target: Exclude<EditTarget, { kind: 'remove' }>; onDone: () => void }) {
  const add = useAddDuty(chart.periodId)
  const replace = useReplaceDuty(chart.periodId)
  const form = useForm<ChangeValues>({ resolver: zodResolver(changeSchema), defaultValues: { facultyId: '', reason: '' } })
  const [problem, setProblem] = useState<string | null>(null)
  const facultyId = useWatch({ control: form.control, name: 'facultyId' })

  const base: ChangeRequest =
    target.kind === 'replace'
      ? { sessionHallId: target.hall.sessionHallId, role: target.duty.role, replacesAssignmentId: target.duty.assignmentId }
      : { sessionHallId: target.hall.sessionHallId, role: target.role }
  const options = useValidateChange(chart.periodId, base)
  const verdict = useValidateChange(chart.periodId, facultyId ? { ...base, facultyId } : null)
  const blocked = verdict.data ? !verdict.data.allowed : false

  const people = chart.workload
    .filter((w) => w.active && (target.kind !== 'replace' || w.facultyId !== target.duty.facultyId))
    .sort((a, b) => a.name.localeCompare(b.name) || a.code.localeCompare(b.code))

  const onSubmit = form.handleSubmit(async ({ facultyId: chosen, reason }) => {
    setProblem(null)
    try {
      if (target.kind === 'replace') {
        await replace.mutateAsync({ assignmentId: target.duty.assignmentId, facultyId: chosen, version: target.duty.version, reason })
      } else {
        await add.mutateAsync({ sessionHallId: target.hall.sessionHallId, role: target.role, facultyId: chosen, reason })
      }
      onDone()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not save the change.')
    }
  })

  const title = target.kind === 'replace' ? `Replace ${target.duty.facultyName}` : 'Fill an open position'
  return (
    <EditPanel title={title} onClose={onDone}>
      <form onSubmit={(ev) => void onSubmit(ev)} noValidate className="space-y-4">
        <p className="text-sm text-neutral-600">{where(target)}</p>
        {problem && <ErrorAlert>{problem}</ErrorAlert>}

        <div>
          <h3 className="text-sm font-medium text-neutral-700">Suggested</h3>
          <p className="text-sm text-neutral-500">Faculty who can take this duty without breaking a strict rule, best fit first.</p>
          {options.isPending && <p className="mt-2 text-sm text-neutral-500">Finding suitable faculty...</p>}
          {options.error && <ErrorAlert>{toApiProblem(options.error).detail ?? 'Could not load suggestions.'}</ErrorAlert>}
          {options.data && options.data.alternatives.length === 0 && (
            <p className="mt-2 text-sm text-neutral-600">Nobody can take this duty without breaking a strict rule.</p>
          )}
          {options.data && options.data.alternatives.length > 0 && (
            <ul className="mt-2 divide-y divide-neutral-100 rounded-md border border-neutral-200">
              {options.data.alternatives.map((a) => (
                <li key={a.facultyId} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                  <div>
                    <span className="font-medium text-neutral-900">{a.name}</span>{' '}
                    <span className="text-neutral-500">
                      ({a.code}, {a.departmentCode}) · {a.duties} dut{a.duties === 1 ? 'y' : 'ies'}
                    </span>
                    {a.warnings.length > 0 && <p className="text-xs text-amber-700">{a.warnings.join('; ')}</p>}
                  </div>
                  <Button
                    type="button"
                    variant="secondary"
                    className="px-3 py-1"
                    aria-label={`Choose ${a.name}`}
                    onClick={() => form.setValue('facultyId', a.facultyId, { shouldValidate: true })}
                  >
                    {facultyId === a.facultyId ? 'Chosen' : 'Choose'}
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <SelectField
          label="Faculty member"
          description="Or pick anyone; the rules are checked as you choose."
          error={form.formState.errors.facultyId?.message}
          options={[
            { value: '', label: 'Choose...' },
            ...people.map((p) => ({
              value: p.facultyId,
              label: `${p.name} (${p.code}, ${p.departmentCode}) · ${p.duties} dut${p.duties === 1 ? 'y' : 'ies'}`,
            })),
          ]}
          {...form.register('facultyId')}
        />

        {facultyId && <Verdict pending={verdict.isFetching} error={verdict.error} data={verdict.data} />}

        <TextField label="Reason" hint="Optional; kept in the edit history" error={form.formState.errors.reason?.message} {...form.register('reason')} />

        <div className="flex gap-2">
          <Button type="submit" loading={add.isPending || replace.isPending} disabled={!facultyId || blocked || verdict.isFetching}>
            {target.kind === 'replace' ? 'Replace' : 'Assign'}
          </Button>
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        </div>
      </form>
    </EditPanel>
  )
}

function Verdict({ pending, error, data }: { pending: boolean; error: unknown; data?: { allowed: boolean; blocking: { code: string; message: string }[]; warnings: string[] } }) {
  if (pending) return <p className="text-sm text-neutral-500">Checking the rules...</p>
  if (error) return <ErrorAlert>{toApiProblem(error).detail ?? 'Could not check this change.'}</ErrorAlert>
  if (!data) return null
  return (
    <div className="space-y-2" aria-live="polite">
      {data.blocking.length > 0 && (
        <div role="alert" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-900">
          <p className="flex items-center gap-2 font-medium">
            <AlertTriangle className="size-4" aria-hidden /> Not allowed
          </p>
          <ul className="mt-1 list-disc pl-5">
            {data.blocking.map((b) => (
              <li key={b.code}>{b.message}</li>
            ))}
          </ul>
        </div>
      )}
      {data.warnings.length > 0 && (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <p className="flex items-center gap-2 font-medium">
            <Info className="size-4" aria-hidden /> {data.allowed ? 'Allowed, but note' : 'Also note'}
          </p>
          <ul className="mt-1 list-disc pl-5">
            {data.warnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
        </div>
      )}
      {data.allowed && data.warnings.length === 0 && (
        <p className="flex items-center gap-2 text-sm text-emerald-700">
          <CheckCircle2 className="size-4" aria-hidden /> Fits every rule.
        </p>
      )}
    </div>
  )
}

const removeSchema = z.object({ reason: reasonSchema })

function RemoveDuty({ periodId, target, onDone }: { periodId: string; target: Extract<EditTarget, { kind: 'remove' }>; onDone: () => void }) {
  const remove = useRemoveDuty(periodId)
  const form = useForm<z.infer<typeof removeSchema>>({ resolver: zodResolver(removeSchema), defaultValues: { reason: '' } })
  const [problem, setProblem] = useState<string | null>(null)

  const onSubmit = form.handleSubmit(async ({ reason }) => {
    setProblem(null)
    try {
      await remove.mutateAsync({ assignmentId: target.duty.assignmentId, version: target.duty.version, reason: reason || undefined })
      onDone()
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not remove the duty.')
    }
  })

  return (
    <EditPanel title={`Remove ${target.duty.facultyName}`} onClose={onDone}>
      <form onSubmit={(ev) => void onSubmit(ev)} noValidate className="space-y-4">
        <p className="text-sm text-neutral-600">
          {target.duty.facultyName} will no longer be {where(target)}. The position becomes open until you fill it.
        </p>
        {problem && <ErrorAlert>{problem}</ErrorAlert>}
        <TextField label="Reason" hint="Optional; kept in the edit history" error={form.formState.errors.reason?.message} {...form.register('reason')} />
        <div className="flex gap-2">
          <Button type="submit" loading={remove.isPending}>Remove duty</Button>
          <Button type="button" variant="secondary" onClick={onDone}>Cancel</Button>
        </div>
      </form>
    </EditPanel>
  )
}

import { CheckCircle2 } from 'lucide-react'
import { useId, type InputHTMLAttributes, type ReactNode, type Ref, type SelectHTMLAttributes } from 'react'

/** Checkbox with a label and an optional explanation underneath. Works with `register`. */
export function CheckboxField({
  label,
  description,
  ref,
  ...input
}: InputHTMLAttributes<HTMLInputElement> & { label: string; description?: string; ref?: Ref<HTMLInputElement> }) {
  const id = useId()
  return (
    <div className="flex items-start gap-3">
      <input
        id={id}
        ref={ref}
        type="checkbox"
        aria-describedby={description ? `${id}-desc` : undefined}
        className="mt-1 size-4 shrink-0 accent-indigo-600 disabled:opacity-60"
        {...input}
      />
      <div>
        <label htmlFor={id} className="text-sm font-medium text-slate-800">
          {label}
        </label>
        {description && (
          <p id={`${id}-desc`} className="text-sm text-slate-500">
            {description}
          </p>
        )}
      </div>
    </div>
  )
}

export interface Option {
  value: string
  label: string
}

export function SelectField({
  label,
  description,
  error,
  options,
  ref,
  ...select
}: SelectHTMLAttributes<HTMLSelectElement> & {
  label: string
  description?: string
  error?: string
  options: Option[]
  ref?: Ref<HTMLSelectElement>
}) {
  const id = useId()
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      {description && <p className="text-sm text-slate-500">{description}</p>}
      <select
        id={id}
        ref={ref}
        aria-invalid={error ? true : undefined}
        className="mt-1 block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 shadow-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 disabled:bg-slate-50 disabled:text-slate-500"
        {...select}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {error && <p className="mt-1 text-sm text-red-600">{error}</p>}
    </div>
  )
}

/** A titled group of related settings. */
export function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <h2 className="text-base font-semibold text-slate-900">{title}</h2>
      {description && <p className="mt-1 text-sm text-slate-600">{description}</p>}
      <div className="mt-4 space-y-5">{children}</div>
    </section>
  )
}

export function SuccessNotice({ children }: { children: ReactNode }) {
  return (
    <div role="status" className="flex items-start gap-2 rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">
      <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div>{children}</div>
    </div>
  )
}

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
        className="mt-1 size-4 shrink-0 accent-ink disabled:opacity-60"
        {...input}
      />
      <div>
        <label htmlFor={id} className="text-sm font-medium text-neutral-800">
          {label}
        </label>
        {description && (
          <p id={`${id}-desc`} className="text-sm text-neutral-500">
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
      <label htmlFor={id} className="block text-sm font-medium text-neutral-900">
        {label}
      </label>
      {description && <p className="text-sm text-neutral-500">{description}</p>}
      <select
        id={id}
        ref={ref}
        aria-invalid={error ? true : undefined}
        className="mt-1.5 block h-10 w-full rounded-md border border-neutral-300 bg-white px-3 text-[15px] text-neutral-900 outline-none transition-colors hover:border-neutral-400 focus:border-ink focus:ring-1 focus:ring-ink disabled:bg-neutral-50 disabled:text-neutral-500 sm:text-sm"
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
    <section className="border-t border-ink pt-4">
      <h2 className="text-lg font-semibold text-neutral-900">{title}</h2>
      {description && <p className="mt-1 text-sm text-neutral-600">{description}</p>}
      <div className="mt-4 space-y-5">{children}</div>
    </section>
  )
}

export function SuccessNotice({ children }: { children: ReactNode }) {
  return (
    <div role="status" className="flex items-start gap-2 border-l-2 border-emerald-600 bg-emerald-50 px-3 py-2.5 text-sm text-emerald-900">
      <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div>{children}</div>
    </div>
  )
}

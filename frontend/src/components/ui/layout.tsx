import { X } from 'lucide-react'
import type { ReactNode } from 'react'

/** Page title block: large flush-left title over a hairline rule, with optional actions on the right. */
export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-col gap-4 border-b border-neutral-200 pb-5 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        <h1 className="text-2xl font-bold text-neutral-900 sm:text-3xl">{title}</h1>
        {description && <p className="mt-2 max-w-2xl text-sm text-neutral-600">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

/** Inline editor shown above a list for create/edit. */
export function EditPanel({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <section aria-label={title} className="border border-ink bg-white p-4 sm:p-5">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-neutral-900">{title}</h2>
        <button type="button" onClick={onClose} className="-m-1.5 p-1.5 text-neutral-500 hover:text-ink" aria-label="Close">
          <X className="size-5" aria-hidden />
        </button>
      </div>
      <div className="mt-4">{children}</div>
    </section>
  )
}

export function StatusBadge({ active }: { active: boolean }) {
  return active ? (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-neutral-900">
      <span className="size-1.5 rounded-full bg-emerald-600" aria-hidden />
      Active
    </span>
  ) : (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-neutral-500">
      <span className="size-1.5 rounded-full bg-neutral-300" aria-hidden />
      Inactive
    </span>
  )
}

/** Data table. Scrolls sideways inside its own frame on narrow screens instead of widening the page. */
export function Table({ head, children, empty }: { head: string[]; children: ReactNode; empty?: string | false }) {
  return (
    <div className="relative -mx-4 overflow-x-auto border-y border-neutral-200 bg-white sm:mx-0 sm:border">
      <table className="min-w-full text-sm">
        <thead className="border-b border-neutral-900 text-left">
          <tr>
            {head.map((h, i) => (
              <th key={i} scope="col" className="whitespace-nowrap px-4 py-2.5 text-xs font-semibold uppercase tracking-wider text-neutral-500">
                {h || <span className="sr-only">Actions</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-neutral-200">{children}</tbody>
      </table>
      {empty && <p className="px-4 py-8 text-center text-sm text-neutral-500">{empty}</p>}
    </div>
  )
}

export function RowActions({ onEdit, onDelete, label }: { onEdit: () => void; onDelete: () => void; label: string }) {
  return (
    <div className="flex justify-end gap-4">
      <button type="button" onClick={onEdit} className="link" aria-label={`Edit ${label}`}>
        Edit
      </button>
      <button type="button" onClick={onDelete} className="text-red-700 underline decoration-red-200 underline-offset-[3px] hover:decoration-current" aria-label={`Delete ${label}`}>
        Delete
      </button>
    </div>
  )
}

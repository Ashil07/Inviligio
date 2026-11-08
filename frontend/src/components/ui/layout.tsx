import { X } from 'lucide-react'
import type { ReactNode } from 'react'

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
        {description && <p className="mt-1 text-sm text-slate-600">{description}</p>}
      </div>
      {actions && <div className="flex gap-2">{actions}</div>}
    </div>
  )
}

/** Inline editor card shown above a list for create/edit. */
export function EditPanel({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-lg border border-indigo-200 bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-slate-900">{title}</h2>
        <button type="button" onClick={onClose} className="rounded p-1 text-slate-500 hover:bg-slate-100" aria-label="Close">
          <X className="size-4" aria-hidden />
        </button>
      </div>
      <div className="mt-4">{children}</div>
    </section>
  )
}

export function StatusBadge({ active }: { active: boolean }) {
  return active ? (
    <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">Active</span>
  ) : (
    <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">Inactive</span>
  )
}

export function Table({ head, children, empty }: { head: string[]; children: ReactNode; empty?: string | false }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50 text-left text-slate-600">
          <tr>
            {head.map((h, i) => (
              <th key={i} scope="col" className="px-4 py-2 font-medium">
                {h || <span className="sr-only">Actions</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">{children}</tbody>
      </table>
      {empty && <p className="px-4 py-6 text-center text-sm text-slate-500">{empty}</p>}
    </div>
  )
}

export function RowActions({ onEdit, onDelete, label }: { onEdit: () => void; onDelete: () => void; label: string }) {
  return (
    <div className="flex justify-end gap-3">
      <button type="button" onClick={onEdit} className="text-indigo-700 hover:underline" aria-label={`Edit ${label}`}>
        Edit
      </button>
      <button type="button" onClick={onDelete} className="text-red-700 hover:underline" aria-label={`Delete ${label}`}>
        Delete
      </button>
    </div>
  )
}

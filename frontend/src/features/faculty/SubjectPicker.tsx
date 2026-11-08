import { X } from 'lucide-react'
import { useDeferredValue, useState } from 'react'
import { TextField } from '@/components/ui/form'
import { useSubjects } from '@/features/subjects/api'
import type { Ref } from './api'

/** Choose the subjects a faculty member teaches: chips for the selection, search to add more. */
export function SubjectPicker({ value, onChange }: { value: Ref[]; onChange: (subjects: Ref[]) => void }) {
  const [search, setSearch] = useState('')
  const q = useDeferredValue(search.trim())
  const results = useSubjects({ q, departmentId: '', includeInactive: false, page: 0, size: 20 })
  const selected = new Set(value.map((s) => s.id))
  const options = (results.data?.items ?? []).filter((s) => !selected.has(s.id))

  return (
    <fieldset>
      <legend className="text-sm font-medium text-slate-700">Subjects taught</legend>
      <p className="text-sm text-slate-500">Used by the own-subject rule: nobody invigilates a hall where their subject is being examined (unless the policy allows it).</p>
      <ul className="mt-2 flex flex-wrap gap-2" aria-label="Selected subjects">
        {value.length === 0 && <li className="text-sm text-slate-500">None selected.</li>}
        {value.map((s) => (
          <li key={s.id} className="inline-flex items-center gap-1 rounded-full bg-indigo-50 py-1 pl-3 pr-1 text-sm text-indigo-800">
            {s.code}
            <button
              type="button"
              onClick={() => onChange(value.filter((v) => v.id !== s.id))}
              className="rounded-full p-0.5 hover:bg-indigo-100"
              aria-label={`Remove ${s.code}`}
            >
              <X className="size-3.5" aria-hidden />
            </button>
          </li>
        ))}
      </ul>
      <div className="mt-3 max-w-md">
        <TextField label="Find a subject to add" placeholder="Code or name" value={search} onChange={(e) => setSearch(e.target.value)} />
        {search.trim() && (
          <ul className="mt-1 max-h-48 overflow-y-auto rounded-md border border-slate-200 bg-white text-sm" aria-label="Matching subjects">
            {options.length === 0 && <li className="px-3 py-2 text-slate-500">No matching subjects.</li>}
            {options.map((s) => (
              <li key={s.id}>
                <button
                  type="button"
                  className="w-full px-3 py-2 text-left hover:bg-slate-50"
                  onClick={() => {
                    onChange([...value, { id: s.id, code: s.code, name: s.name }])
                    setSearch('')
                  }}
                >
                  <span className="font-medium">{s.code}</span> <span className="text-slate-600">{s.name}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </fieldset>
  )
}

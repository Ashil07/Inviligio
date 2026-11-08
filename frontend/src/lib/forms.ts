import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import type { ApiProblem } from '@/lib/api/client'

/**
 * Puts backend validation messages (`fieldErrors`, keyed by dotted path) onto form fields.
 * `prefix` strips a request wrapper, e.g. "settings." for `{ basedOnVersion, settings }`.
 * Returns true when at least one field error was applied.
 */
export function applyFieldErrors<T extends FieldValues>(problem: ApiProblem, setError: UseFormSetError<T>, prefix = ''): boolean {
  const entries = Object.entries(problem.fieldErrors ?? {})
  for (const [path, message] of entries) {
    const field = path.startsWith(prefix) ? path.slice(prefix.length) : path
    setError(field as Path<T>, { message })
  }
  return entries.length > 0
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

/** `register` value converters for number inputs. */
export const asNumber = (v: unknown) => (v === '' || v === null || v === undefined ? Number.NaN : Number(v))
export const asNumberOrNull = (v: unknown) => (v === '' || v === null || v === undefined ? null : Number(v))

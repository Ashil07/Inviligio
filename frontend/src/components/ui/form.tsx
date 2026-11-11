import { AlertCircle } from 'lucide-react'
import { useId, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type Ref } from 'react'

interface TextFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  error?: string
  hint?: string
  ref?: Ref<HTMLInputElement>
}

/** Labelled input with an accessible error message. Works with react-hook-form's `register`. */
export function TextField({ label, error, hint, className, ref, ...input }: TextFieldProps) {
  const id = useId()
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-neutral-900">
        {label}
      </label>
      <input
        id={id}
        ref={ref}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`mt-1.5 block h-10 w-full rounded-md border bg-white px-3 text-[15px] text-neutral-900 outline-none transition-colors placeholder:text-neutral-400 focus:ring-1 sm:text-sm ${
          error ? 'border-red-500 focus:border-red-600 focus:ring-red-600' : 'border-neutral-300 hover:border-neutral-400 focus:border-ink focus:ring-ink'
        } ${className ?? ''}`}
        {...input}
      />
      {error ? (
        <p id={`${id}-error`} className="mt-1 text-sm text-red-600">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="mt-1 text-sm text-neutral-500">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

export function Button({
  children,
  loading,
  variant = 'primary',
  className,
  disabled,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { loading?: boolean; variant?: 'primary' | 'secondary' }) {
  const styles =
    variant === 'primary'
      ? 'bg-ink text-white hover:bg-accent disabled:bg-neutral-400'
      : 'border border-neutral-300 bg-white text-neutral-900 hover:border-ink disabled:opacity-50'
  return (
    <button
      {...props}
      disabled={disabled || loading}
      className={`inline-flex h-10 items-center justify-center gap-2 rounded-md px-4 text-sm font-medium transition-colors disabled:cursor-not-allowed ${styles} ${className ?? ''}`}
    >
      {loading ? 'Please wait...' : children}
    </button>
  )
}

export function ErrorAlert({ children }: { children: ReactNode }) {
  return (
    <div role="alert" className="flex items-start gap-2 border-l-2 border-red-600 bg-red-50 px-3 py-2.5 text-sm text-red-900">
      <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div>{children}</div>
    </div>
  )
}

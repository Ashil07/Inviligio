import { Loader2 } from 'lucide-react'

export function FullPageSpinner({ label }: { label: string }) {
  return (
    <div className="flex min-h-dvh items-center justify-center" role="status" aria-live="polite">
      <Loader2 className="size-6 animate-spin text-slate-400" aria-hidden />
      <span className="sr-only">{label}</span>
    </div>
  )
}

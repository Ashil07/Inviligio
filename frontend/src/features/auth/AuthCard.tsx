import type { ReactNode } from 'react'

/** Centered card used by the sign-in and registration pages. */
export function AuthCard({ title, subtitle, children, footer }: {
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center px-4 py-12">
      <p className="text-sm font-semibold tracking-wide text-indigo-700">INVIGILO</p>
      <div className="mt-4 w-full max-w-md rounded-lg border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
        <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-slate-600">{subtitle}</p>}
        <div className="mt-6">{children}</div>
      </div>
      {footer && <div className="mt-6 text-sm text-slate-600">{footer}</div>}
    </main>
  )
}

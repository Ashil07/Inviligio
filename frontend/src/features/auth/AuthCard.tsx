import type { ReactNode } from 'react'
import { BrandMark, Wordmark } from '@/components/Brand'

const YEAR = new Date().getFullYear()

/** Sign-in, registration and invitation pages: a black brand panel on wide screens, the form on the right. */
export function AuthCard({ title, subtitle, children, footer }: {
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <div className="grid min-h-dvh bg-white lg:grid-cols-12">
      <aside className="hidden flex-col justify-between bg-ink p-10 text-white lg:col-span-5 lg:flex xl:col-span-4">
        <Wordmark inverse />
        <div>
          <BrandMark className="size-10" />
          <p className="mt-8 text-4xl font-bold leading-[1.05] tracking-tight">
            Fair duties.
            <br />
            Every exam.
            <br />
            <span className="text-accent">No clashes.</span>
          </p>
          <p className="mt-6 max-w-sm text-sm leading-relaxed text-neutral-400">
            Invigilation duty allocation for colleges: within your rules, balanced across faculty, every change on record.
          </p>
        </div>
        <p className="text-xs text-neutral-500">© {YEAR} Invigilo</p>
      </aside>

      <main className="flex flex-col px-4 py-6 sm:px-10 lg:col-span-7 xl:col-span-8">
        <div className="lg:hidden">
          <Wordmark />
        </div>
        <div className="flex flex-1 flex-col justify-center py-10">
          <div className="mx-auto w-full max-w-md">
            <h1 className="text-3xl font-bold text-neutral-900">{title}</h1>
            {subtitle && <p className="mt-2 text-sm text-neutral-600">{subtitle}</p>}
            <div className="mt-8">{children}</div>
            {footer && <div className="mt-8 border-t border-neutral-200 pt-6 text-sm text-neutral-600">{footer}</div>}
          </div>
        </div>
      </main>
    </div>
  )
}

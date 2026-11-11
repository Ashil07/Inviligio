import { ArrowRight, Check } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Wordmark } from '@/components/Brand'

const YEAR = new Date().getFullYear()

/** Public landing page at `/` for visitors who are not signed in. Every link goes to a real page or section. */

const CONTAINER = 'mx-auto w-full max-w-[1200px] px-4 sm:px-6 lg:px-10'

const STEPS = [
  {
    title: 'Set up',
    text: 'Add departments, subjects, halls and faculty, or import them from a CSV file. Write down your rules once: duty limits, gaps, leave and who may be chief.',
  },
  {
    title: 'Schedule',
    text: 'Create the examination period and its sessions: which subjects are written, in which halls, and for how many students.',
  },
  {
    title: 'Allocate',
    text: 'Generate the duty chart for the whole period. Review it, adjust it by hand with a live rule check, then publish it to faculty.',
  },
  {
    title: 'Run the exams',
    text: 'Faculty see their duties, ask for leave and swap with colleagues. The exam cell assigns substitutes and records attendance.',
  },
]

const FEATURES = [
  {
    title: 'Rules come first',
    text: 'Approved leave, unavailability, subject clashes and duty limits are hard rules. The generator, the editor, swaps and substitutes all check the same rules, so none of them can break one.',
  },
  {
    title: 'Balanced workload',
    text: 'Duties are spread evenly across faculty. The fairness view shows who is under or over the average, by department and by person.',
  },
  {
    title: 'Swaps and substitutes',
    text: 'Faculty propose swaps, colleagues accept and the HOD or exam cell approves. Substitutes are suggested only from people who satisfy every rule.',
  },
  {
    title: 'Reports in three formats',
    text: 'Duty chart, workload and fairness reports as PDF to print, Excel to work with, or CSV for other systems.',
  },
  {
    title: 'Remuneration',
    text: 'Set a pay rate per duty, mark who was absent, and get the pay sheet for the whole period.',
  },
  {
    title: 'Every change on record',
    text: 'Each published chart is a version you can open again, and every change to duties is recorded with who made it and when.',
  },
]

const ROLES = [
  {
    name: 'Examination cell',
    points: ['Keeps faculty, halls and the examination policy', 'Schedules periods and sessions', 'Generates, edits and publishes duty charts', 'Approves swaps, assigns substitutes, exports reports'],
  },
  {
    name: 'Head of department',
    points: ["Sees the department's duty load at a glance", 'Approves or declines leave requests', 'Approves swap requests in the department'],
  },
  {
    name: 'Faculty',
    points: ['Sees the next duty and a calendar of all duties', 'Downloads duties into a phone calendar', 'Asks for leave and proposes swaps'],
  },
]

const SPECIMEN = [
  { hall: 'A-101', chief: 'R. Iyer', invigilators: 'S. Menon, K. Das' },
  { hall: 'A-102', chief: 'P. Nair', invigilators: 'A. Rao, M. Joseph' },
  { hall: 'B-201', chief: 'L. Thomas', invigilators: 'V. Pillai, N. Khan' },
  { hall: 'B-202', chief: 'D. George', invigilators: 'H. Varma, T. Paul' },
]

export function LandingPage() {
  return (
    <div className="min-h-dvh bg-white text-ink">
      <SiteHeader />
      <main>
        <Hero />
        <Facts />
        <Section id="how" eyebrow="How it works" title="From the exam timetable to a published duty chart.">
          <ol className="grid gap-px overflow-hidden border border-neutral-200 bg-neutral-200 sm:grid-cols-2">
            {STEPS.map((s, i) => (
              <li key={s.title} className="bg-white p-6">
                <p className="text-4xl font-bold tracking-tight text-accent">{String(i + 1).padStart(2, '0')}</p>
                <h3 className="mt-6 text-lg font-semibold">{s.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-neutral-600">{s.text}</p>
              </li>
            ))}
          </ol>
        </Section>

        <Section id="features" eyebrow="Features" title="Built for the examination cell, fair to every teacher.">
          <div className="grid gap-x-10 gap-y-10 sm:grid-cols-2 lg:grid-cols-3">
            {FEATURES.map((f) => (
              <div key={f.title} className="border-t-2 border-ink pt-4">
                <h3 className="text-lg font-semibold">{f.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-neutral-600">{f.text}</p>
              </div>
            ))}
          </div>
        </Section>

        <Section id="roles" eyebrow="Roles" title="One system. A view for each person.">
          <div className="grid gap-px overflow-hidden border border-neutral-200 bg-neutral-200 lg:grid-cols-3">
            {ROLES.map((r) => (
              <div key={r.name} className="bg-white p-6 sm:p-8">
                <h3 className="text-xl font-bold">{r.name}</h3>
                <ul className="mt-5 space-y-3">
                  {r.points.map((p) => (
                    <li key={p} className="flex gap-3 text-sm text-neutral-700">
                      <Check className="mt-0.5 size-4 shrink-0 text-accent" strokeWidth={2.5} aria-hidden />
                      {p}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </Section>

        <CallToAction />
      </main>
      <SiteFooter />
    </div>
  )
}

function SiteHeader() {
  return (
    <header className="sticky top-0 z-20 border-b border-neutral-200 bg-white/95 backdrop-blur">
      <div className={`${CONTAINER} flex h-16 items-center gap-6`}>
        <Wordmark />
        <nav aria-label="Sections" className="hidden items-center gap-6 text-sm text-neutral-600 md:flex">
          <a href="#how" className="hover:text-ink">How it works</a>
          <a href="#features" className="hover:text-ink">Features</a>
          <a href="#roles" className="hover:text-ink">Roles</a>
        </nav>
        <div className="ml-auto flex items-center gap-2 sm:gap-4">
          <Link to="/login" className="px-2 py-2 text-sm font-medium text-ink hover:text-accent">
            Sign in
          </Link>
          <Link to="/register" className="inline-flex h-9 items-center bg-ink px-3 text-sm font-medium text-white transition-colors hover:bg-accent sm:px-4">
            <span className="sm:hidden">Register</span>
            <span className="hidden sm:inline">Register your college</span>
          </Link>
        </div>
      </div>
    </header>
  )
}

function Hero() {
  return (
    <section className={`${CONTAINER} grid grid-cols-1 gap-12 pb-16 pt-12 sm:pt-16 lg:grid-cols-12 lg:gap-10 lg:pb-24 lg:pt-24`}>
      <div className="lg:col-span-7">
        <p className="flex items-center gap-2 text-sm font-medium text-neutral-600">
          <span className="size-2.5 bg-accent" aria-hidden />
          Invigilation duty allocation for colleges
        </p>
        <h1 className="mt-6 text-[2.75rem] font-bold leading-[0.95] tracking-tighter sm:text-6xl lg:text-7xl">
          Fair duties.
          <br />
          Every exam.
          <br />
          <span className="text-accent">No clashes.</span>
        </h1>
        <p className="mt-8 max-w-xl text-lg leading-relaxed text-neutral-600">
          Invigilo builds the invigilation duty chart for a whole examination period, within your college's rules and balanced
          across faculty, and keeps every later change on record.
        </p>
        <div className="mt-10 flex flex-col gap-3 sm:flex-row">
          <Link
            to="/register"
            className="inline-flex h-12 items-center justify-center gap-2 bg-ink px-6 text-sm font-medium text-white transition-colors hover:bg-accent"
          >
            Register your college <ArrowRight className="size-4" aria-hidden />
          </Link>
          <Link
            to="/login"
            className="inline-flex h-12 items-center justify-center border border-neutral-300 px-6 text-sm font-medium text-ink transition-colors hover:border-ink"
          >
            Sign in
          </Link>
        </div>
        <p className="mt-4 text-xs text-neutral-500">Whoever registers becomes the college's first exam-cell administrator.</p>
      </div>

      <figure className="self-end lg:col-span-5" aria-label="Example of a duty chart session">
        <div className="border-t-4 border-ink">
          <div className="flex items-baseline justify-between gap-4 py-3">
            <p className="text-sm font-semibold">Mon 9 Mar · Forenoon</p>
            <p className="text-xs text-neutral-500">09:30 to 12:30</p>
          </div>
          <table className="w-full text-left text-sm">
            <thead className="border-y border-neutral-900">
              <tr>
                <th scope="col" className="py-2 pr-3 text-xs font-semibold uppercase tracking-wider text-neutral-500">Hall</th>
                <th scope="col" className="py-2 pr-3 text-xs font-semibold uppercase tracking-wider text-neutral-500">Chief</th>
                <th scope="col" className="py-2 text-xs font-semibold uppercase tracking-wider text-neutral-500">Invigilators</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-200">
              {SPECIMEN.map((r) => (
                <tr key={r.hall}>
                  <td className="py-2.5 pr-3 font-medium">{r.hall}</td>
                  <td className="py-2.5 pr-3">{r.chief}</td>
                  <td className="py-2.5 text-neutral-600">{r.invigilators}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mt-px grid grid-cols-2 border-t border-neutral-900 text-xs">
            <p className="flex items-center gap-1.5 py-3 font-medium">
              <Check className="size-3.5 text-emerald-600" strokeWidth={3} aria-hidden /> All rules satisfied
            </p>
            <p className="py-3 text-right text-neutral-500">12 of 12 positions filled</p>
          </div>
        </div>
        <figcaption className="mt-2 text-xs text-neutral-400">Example with made-up names.</figcaption>
      </figure>
    </section>
  )
}

function Facts() {
  const facts: { value: string; label: string }[] = [
    { value: '0', label: 'hard-rule violations allowed, by the generator or by hand' },
    { value: '6.3 s', label: 'to fill 1,359 positions for a 300-faculty test college' },
    { value: '3', label: 'roles: exam cell, head of department, faculty' },
    { value: '3', label: 'report formats: PDF, Excel and CSV' },
  ]
  return (
    <section aria-label="In numbers" className="border-y border-neutral-200">
      <dl className={`${CONTAINER} grid grid-cols-2 lg:grid-cols-4`}>
        {facts.map((f, i) => (
          <div
            key={f.label}
            className={`py-8 pr-4 ${i % 2 === 1 ? 'border-l border-neutral-200 pl-4 sm:pl-6' : ''} ${i >= 2 ? 'border-t border-neutral-200 lg:border-t-0' : ''} ${i === 2 ? 'lg:border-l lg:pl-6' : ''}`}
          >
            <dt className="sr-only">{f.label}</dt>
            <dd>
              <span className="block text-4xl font-bold tracking-tight sm:text-5xl">{f.value}</span>
              <span className="mt-2 block text-sm text-neutral-600">{f.label}</span>
            </dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

function Section({ id, eyebrow, title, children }: { id: string; eyebrow: string; title: string; children: ReactNode }) {
  return (
    <section id={id} className="scroll-mt-16 py-16 lg:py-24">
      <div className={`${CONTAINER} grid grid-cols-1 gap-10 lg:grid-cols-12`}>
        <div className="lg:col-span-4">
          <p className="eyebrow text-accent">{eyebrow}</p>
          <h2 className="mt-3 text-3xl font-bold leading-tight tracking-tight sm:text-4xl">{title}</h2>
        </div>
        <div className="lg:col-span-8">{children}</div>
      </div>
    </section>
  )
}

function CallToAction() {
  return (
    <section className="bg-ink text-white">
      <div className={`${CONTAINER} grid grid-cols-1 gap-8 py-16 lg:grid-cols-12 lg:items-end lg:py-24`}>
        <h2 className="text-3xl font-bold leading-tight tracking-tight sm:text-5xl lg:col-span-8">
          Ready for the next examination period<span className="text-accent">?</span>
        </h2>
        <div className="flex flex-col gap-3 sm:flex-row lg:col-span-4 lg:justify-end">
          <Link to="/register" className="inline-flex h-12 items-center justify-center gap-2 bg-accent px-6 text-sm font-medium text-white transition-colors hover:bg-accent-dark">
            Register your college <ArrowRight className="size-4" aria-hidden />
          </Link>
          <Link to="/login" className="inline-flex h-12 items-center justify-center border border-white/30 px-6 text-sm font-medium transition-colors hover:border-white">
            Sign in
          </Link>
        </div>
      </div>
    </section>
  )
}

function SiteFooter() {
  return (
    <footer className="border-t border-neutral-200">
      <div className={`${CONTAINER} flex flex-col gap-6 py-10 sm:flex-row sm:items-center sm:justify-between`}>
        <div>
          <Wordmark />
          <p className="mt-2 text-sm text-neutral-500">Invigilation duty allocation for colleges.</p>
        </div>
        <nav aria-label="Footer" className="flex flex-wrap gap-x-6 gap-y-2 text-sm text-neutral-600">
          <Link to="/login" className="hover:text-ink">Sign in</Link>
          <Link to="/register" className="hover:text-ink">Register your college</Link>
          <Link to="/status" className="hover:text-ink">System status</Link>
        </nav>
      </div>
      <div className={`${CONTAINER} border-t border-neutral-200 py-6 text-xs text-neutral-400`}>© {YEAR} Invigilo</div>
    </footer>
  )
}

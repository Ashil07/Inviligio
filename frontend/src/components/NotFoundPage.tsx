import { Link } from 'react-router'
import { Wordmark } from './Brand'

export function NotFoundPage() {
  return (
    <div className="min-h-dvh bg-white">
      <header className="mx-auto max-w-[1200px] px-4 py-5 sm:px-6 lg:px-10">
        <Wordmark />
      </header>
      <main className="mx-auto max-w-[1200px] px-4 py-20 sm:px-6 lg:px-10">
        <p className="text-7xl font-bold tracking-tighter text-accent sm:text-8xl">404</p>
        <h1 className="mt-6 text-3xl font-bold text-neutral-900">Page not found</h1>
        <p className="mt-2 text-neutral-600">The page you are looking for does not exist.</p>
        <Link to="/" className="link mt-8 inline-block text-sm font-medium">
          Go to home
        </Link>
      </main>
    </div>
  )
}

import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <main className="mx-auto max-w-2xl px-4 py-24 text-center">
      <h1 className="text-2xl font-semibold text-slate-900">Page not found</h1>
      <p className="mt-2 text-slate-600">The page you are looking for does not exist.</p>
      <Link to="/" className="mt-6 inline-block text-sm font-medium text-indigo-700 hover:underline">
        Go to home
      </Link>
    </main>
  )
}

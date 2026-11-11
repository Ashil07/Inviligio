import { Link } from 'react-router'

/** The Invigilo mark: a red square with a white "i". Matches public/favicon.svg. */
export function BrandMark({ className = 'size-6' }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden focusable="false">
      <rect width="32" height="32" fill="#e30613" />
      <rect x="13" y="6" width="6" height="5" fill="#fff" />
      <rect x="13" y="13" width="6" height="13" fill="#fff" />
    </svg>
  )
}

/** Mark plus name, linking home. `inverse` is for dark backgrounds. */
export function Wordmark({ to = '/', inverse = false }: { to?: string; inverse?: boolean }) {
  return (
    <Link to={to} className={`inline-flex items-center gap-2.5 ${inverse ? 'text-white' : 'text-ink'}`} aria-label="Invigilo home">
      <BrandMark />
      <span className="text-lg font-bold tracking-tight">Invigilo</span>
    </Link>
  )
}

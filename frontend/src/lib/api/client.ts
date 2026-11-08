import axios, { AxiosError } from 'axios'

/**
 * Single Axios instance for the Invigilo API.
 *
 * - Dev: VITE_API_BASE_URL is unset, requests go to /api on the Vite server, which proxies to :8080.
 * - Prod: VITE_API_BASE_URL is the deployed API origin, e.g. https://api.example.com
 *
 * The auth module registers a token provider with `setAccessTokenProvider`; the token itself
 * is kept in memory by the auth module, never in localStorage.
 */
export const api = axios.create({
  baseURL: `${import.meta.env.VITE_API_BASE_URL ?? ''}/api/v1`,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000,
})

let accessTokenProvider: () => string | null = () => null

export function setAccessTokenProvider(provider: () => string | null) {
  accessTokenProvider = provider
}

api.interceptors.request.use((config) => {
  const token = accessTokenProvider()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

/** RFC 9457 problem body produced by the backend's GlobalExceptionHandler. */
export interface ApiProblem {
  status: number
  title?: string
  detail?: string
  code?: string
  fieldErrors?: Record<string, string>
}

/** Normalises any thrown value (Axios error, network failure, bug) into an ApiProblem. */
export function toApiProblem(error: unknown): ApiProblem {
  if (error instanceof AxiosError) {
    const data = error.response?.data as Partial<ApiProblem> | undefined
    if (error.response && data && typeof data === 'object') {
      return { ...data, status: error.response.status }
    }
    if (error.response) {
      return { status: error.response.status, detail: error.message }
    }
    return { status: 0, code: 'NETWORK_ERROR', detail: 'Cannot reach the Invigilo server.' }
  }
  return { status: 0, code: 'UNKNOWN_ERROR', detail: error instanceof Error ? error.message : String(error) }
}

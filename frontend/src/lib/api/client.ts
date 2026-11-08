import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'

/**
 * Single Axios instance for the Invigilo API.
 *
 * - Dev: VITE_API_BASE_URL is unset, requests go to /api on the Vite server, which proxies to :8080.
 * - Prod: VITE_API_BASE_URL is the deployed API origin, e.g. https://api.example.com
 *
 * The access token lives in memory only (see features/auth). The refresh token is an HttpOnly
 * cookie the browser sends to /api/v1/auth/* by itself, hence `withCredentials`.
 */
export const api = axios.create({
  baseURL: `${import.meta.env.VITE_API_BASE_URL ?? ''}/api/v1`,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000,
  withCredentials: true,
})

type TokenProvider = () => string | null
type SessionRefresher = () => Promise<string | null>

let accessTokenProvider: TokenProvider = () => null
let sessionRefresher: SessionRefresher = async () => null

/** Registered by the auth module: where to read the current access token from. */
export function setAccessTokenProvider(provider: TokenProvider) {
  accessTokenProvider = provider
}

/** Registered by the auth module: obtains a fresh access token (or null when signed out). */
export function setSessionRefresher(refresher: SessionRefresher) {
  sessionRefresher = refresher
}

/**
 * Credential endpoints never carry a bearer token: a stale token on a public endpoint would be
 * rejected by the API before the endpoint runs. /auth/me is a normal authenticated call.
 */
function isCredentialEndpoint(url: string | undefined) {
  return !!url && /\/auth\/(login|register-college|refresh|logout)$/.test(url)
}

api.interceptors.request.use((config) => {
  // A replayed request already carries the token it was refreshed with.
  if (isCredentialEndpoint(config.url) || (config as RetriableConfig)._retried) return config
  const token = accessTokenProvider()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

/** On 401, refresh the session once and replay the request with the new token. */
api.interceptors.response.use(undefined, async (error: unknown) => {
  if (!(error instanceof AxiosError) || error.response?.status !== 401 || !error.config) {
    throw error
  }
  const config = error.config as RetriableConfig
  if (config._retried || isCredentialEndpoint(config.url)) throw error
  config._retried = true
  const token = await sessionRefresher()
  if (!token) throw error
  config.headers.Authorization = `Bearer ${token}`
  return api.request(config)
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

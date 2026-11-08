import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, setAccessTokenProvider, setSessionRefresher } from './client'

function respond(config: InternalAxiosRequestConfig, status: number, data: unknown = {}) {
  const response = { data, status, statusText: String(status), headers: {}, config }
  if (status >= 400) {
    return Promise.reject(new AxiosError(`HTTP ${status}`, 'ERR_BAD_RESPONSE', config, null, response))
  }
  return Promise.resolve(response)
}

describe('api client', () => {
  const originalAdapter = api.defaults.adapter
  let seenAuth: (string | undefined)[]

  beforeEach(() => {
    seenAuth = []
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    setAccessTokenProvider(() => null)
    setSessionRefresher(async () => null)
  })

  function useAdapter(handler: (config: InternalAxiosRequestConfig) => ReturnType<AxiosAdapter>) {
    api.defaults.adapter = (config) => {
      seenAuth.push(AxiosHeaders.from(config.headers).get('Authorization')?.toString())
      return handler(config)
    }
  }

  it('attaches the bearer token to normal requests', async () => {
    setAccessTokenProvider(() => 'abc')
    useAdapter((c) => respond(c, 200))

    await api.get('/auth/me')

    expect(seenAuth).toEqual(['Bearer abc'])
  })

  it('never sends the bearer token to credential endpoints', async () => {
    setAccessTokenProvider(() => 'stale')
    useAdapter((c) => respond(c, 200))

    await api.post('/auth/login', {})
    await api.post('/auth/refresh')

    expect(seenAuth).toEqual([undefined, undefined])
  })

  it('refreshes once on 401 and replays the request with the new token', async () => {
    setAccessTokenProvider(() => 'expired')
    const refresher = vi.fn(async () => 'fresh')
    setSessionRefresher(refresher)
    useAdapter((c) => {
      const auth = AxiosHeaders.from(c.headers).get('Authorization')?.toString()
      return respond(c, auth === 'Bearer fresh' ? 200 : 401, { ok: auth === 'Bearer fresh' })
    })

    const { data } = await api.get('/auth/me')

    expect(data).toEqual({ ok: true })
    expect(refresher).toHaveBeenCalledTimes(1)
    expect(seenAuth).toEqual(['Bearer expired', 'Bearer fresh'])
  })

  it('gives up when the refresh fails', async () => {
    setSessionRefresher(async () => null)
    useAdapter((c) => respond(c, 401))

    await expect(api.get('/auth/me')).rejects.toMatchObject({ response: { status: 401 } })
    expect(seenAuth).toHaveLength(1)
  })

  it('does not loop when the replayed request is also rejected', async () => {
    const refresher = vi.fn(async () => 'fresh')
    setSessionRefresher(refresher)
    useAdapter((c) => respond(c, 401))

    await expect(api.get('/auth/me')).rejects.toBeInstanceOf(AxiosError)
    expect(refresher).toHaveBeenCalledTimes(1)
    expect(seenAuth).toHaveLength(2)
  })
})

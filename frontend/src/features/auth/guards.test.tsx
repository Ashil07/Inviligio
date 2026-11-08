import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import { sessionStore } from './sessionStore'

describe('route guards', () => {
  afterEach(() => vi.restoreAllMocks())

  it('sends anonymous visitors to sign-in', async () => {
    const { router } = renderRoutes(routes, { path: '/admin' })

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('lands each user in the area of their highest role', async () => {
    const { router } = renderRoutes(routes, { path: '/', auth: makeAuth(makeSession(['FACULTY', 'HOD'])) })

    expect(await screen.findByRole('heading', { name: 'Department' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/hod')
    expect(screen.getByRole('navigation', { name: 'Areas' })).toBeInTheDocument()
  })

  it('blocks areas of roles the user does not hold', async () => {
    renderRoutes(routes, { path: '/admin', auth: makeAuth(makeSession(['FACULTY'])) })

    expect(await screen.findByRole('heading', { name: 'No access' })).toBeInTheDocument()
  })

  it('keeps signed-in users away from the sign-in page', async () => {
    const { router } = renderRoutes(routes, { path: '/login', auth: makeAuth() })

    expect(await screen.findByRole('heading', { name: 'Examination cell' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/admin')
  })

  it('signs out through the API and returns to sign-in', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: undefined })
    renderRoutes(routes, { path: '/admin', auth: makeAuth() })

    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/auth/logout')
    expect(sessionStore.get()).toBeNull()
  })
})

import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import { routes } from '@/app/router'
import { sessionStore } from './sessionStore'

function problem(status: number, body: object) {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('fail', 'ERR', config, null, { status, statusText: '', headers: {}, config, data: body })
}

describe('LoginPage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('validates before calling the API', async () => {
    const post = vi.spyOn(api, 'post')
    renderRoutes(routes, { path: '/login' })

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Email is required')).toBeInTheDocument()
    expect(screen.getByText('Password is required')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('signs in and lands in the role area', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { status: 'AUTHENTICATED', auth: makeAuth() } })
    renderRoutes(routes, { path: '/login' })

    await userEvent.type(screen.getByLabelText('Email'), ' admin@example.test ')
    await userEvent.type(screen.getByLabelText('Password'), 'secret-pass')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Examination cell' })).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/auth/login', {
      email: 'admin@example.test',
      password: 'secret-pass',
      collegeId: undefined,
    })
    expect(sessionStore.get()?.accessToken).toBe('token-1')
  })

  it('asks which college when the account has several, then sends the choice', async () => {
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValueOnce({
        data: {
          status: 'COLLEGE_SELECTION_REQUIRED',
          colleges: [
            { id: 'c1', code: 'GEC', name: 'Government Engineering College' },
            { id: 'c2', code: 'SNC', name: 'Sree Narayana College' },
          ],
        },
      })
      .mockResolvedValueOnce({ data: { status: 'AUTHENTICATED', auth: makeAuth(makeSession(['FACULTY'])) } })
    renderRoutes(routes, { path: '/login' })

    await userEvent.type(screen.getByLabelText('Email'), 'a@example.test')
    await userEvent.type(screen.getByLabelText('Password'), 'pw')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await userEvent.click(await screen.findByLabelText(/Sree Narayana College/))
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByRole('heading', { name: 'My duties' })).toBeInTheDocument()
    expect(post).toHaveBeenLastCalledWith('/auth/login', { email: 'a@example.test', password: 'pw', collegeId: 'c2' })
  })

  it('shows the server message on bad credentials', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(
      problem(401, { code: 'INVALID_CREDENTIALS', detail: 'Incorrect email or password' }),
    )
    renderRoutes(routes, { path: '/login' })

    await userEvent.type(screen.getByLabelText('Email'), 'a@example.test')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Incorrect email or password')
    await waitFor(() => expect(sessionStore.get()).toBeNull())
  })
})

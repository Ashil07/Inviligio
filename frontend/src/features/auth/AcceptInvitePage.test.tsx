import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import { sessionStore } from './sessionStore'

const preview = {
  collegeName: 'Government Engineering College',
  collegeCode: 'GEC',
  facultyName: 'Ravi Kumar',
  email: 'ravi@college.example',
  roles: ['HOD', 'FACULTY'],
  expiresAt: '2026-11-15T10:00:00Z',
  existingAccount: false,
}

function mockPost(existingAccount: boolean, acceptResult: unknown) {
  return vi.spyOn(api, 'post').mockImplementation(async (url: string) => {
    if (url === '/auth/invitations/preview') return { data: { ...preview, existingAccount } }
    if (url === '/auth/invitations/accept') {
      if (acceptResult instanceof Error) throw acceptResult
      return { data: acceptResult }
    }
    throw new Error(url)
  })
}

describe('AcceptInvitePage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('creates a new account and signs in', async () => {
    const faculty = makeAuth(makeSession(['FACULTY'], { facultyId: 'f1' }))
    const post = mockPost(false, faculty)
    renderRoutes(routes, { path: '/accept-invite?token=abc' })

    expect(await screen.findByRole('heading', { name: 'Join Government Engineering College' })).toBeInTheDocument()
    expect(screen.getByLabelText('Your full name')).toHaveValue('Ravi Kumar')
    await userEvent.type(screen.getByLabelText('Choose a password'), 'long-password')
    await userEvent.type(screen.getByLabelText('Confirm password'), 'long-password')
    await userEvent.click(screen.getByRole('button', { name: 'Create account and sign in' }))

    expect(await screen.findByRole('heading', { name: 'My duties' })).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/auth/invitations/accept', { token: 'abc', password: 'long-password', fullName: 'Ravi Kumar' })
    expect(sessionStore.get()?.session.facultyId).toBe('f1')
  })

  it('asks an existing account for its password only', async () => {
    const config = { headers: new AxiosHeaders() }
    mockPost(true, new AxiosError('x', 'ERR', config, null, {
      status: 401, statusText: '', headers: {}, config,
      data: { code: 'INVALID_CREDENTIALS', detail: 'An account for ravi@college.example already exists. Enter its current password.' },
    }))
    renderRoutes(routes, { path: '/accept-invite?token=abc' })

    await userEvent.type(await screen.findByLabelText('Password'), 'wrong')
    expect(screen.queryByLabelText('Confirm password')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Link and sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter its current password')
  })

  it('explains an invalid link', async () => {
    const config = { headers: new AxiosHeaders() }
    vi.spyOn(api, 'post').mockRejectedValue(new AxiosError('x', 'ERR', config, null, {
      status: 404, statusText: '', headers: {}, config,
      data: { code: 'INVITATION_INVALID', detail: 'This invitation link is invalid or has expired.' },
    }))
    renderRoutes(routes, { path: '/accept-invite?token=used' })

    expect(await screen.findByRole('heading', { name: 'Invitation not valid' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('invalid or has expired'))
  })
})

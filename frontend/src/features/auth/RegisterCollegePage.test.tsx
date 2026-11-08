import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, renderRoutes } from '@/test/render'

async function fill(overrides: Partial<Record<string, string>> = {}) {
  const values = {
    'College name': 'Government Engineering College',
    'College code': 'gec-tcr',
    'Your full name': 'Asha Admin',
    Email: 'asha@example.test',
    Password: 'long-enough-pw',
    'Confirm password': 'long-enough-pw',
    ...overrides,
  }
  for (const [label, value] of Object.entries(values)) {
    if (value) await userEvent.type(screen.getByLabelText(label), value)
  }
  await userEvent.click(screen.getByRole('button', { name: 'Create college account' }))
}

describe('RegisterCollegePage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('rejects mismatched passwords without calling the API', async () => {
    const post = vi.spyOn(api, 'post')
    renderRoutes(routes, { path: '/register' })

    await fill({ 'Confirm password': 'something-else' })

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('registers, signs in and opens the exam cell area', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: makeAuth() })
    renderRoutes(routes, { path: '/register' })

    await fill()

    expect(await screen.findByText('College setup is not finished')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/auth/register-college', {
      collegeName: 'Government Engineering College',
      collegeCode: 'gec-tcr',
      adminFullName: 'Asha Admin',
      adminEmail: 'asha@example.test',
      password: 'long-enough-pw',
    })
  })

  it('shows a duplicate-code conflict from the server', async () => {
    const config = { headers: new AxiosHeaders() }
    vi.spyOn(api, 'post').mockRejectedValue(
      new AxiosError('conflict', 'ERR', config, null, {
        status: 409,
        statusText: '',
        headers: {},
        config,
        data: { code: 'COLLEGE_CODE_TAKEN', detail: 'A college with this code is already registered' },
      }),
    )
    renderRoutes(routes, { path: '/register' })

    await fill()

    expect(await screen.findByRole('alert')).toHaveTextContent('A college with this code is already registered')
  })

  it('maps server field errors onto the fields', async () => {
    const config = { headers: new AxiosHeaders() }
    vi.spyOn(api, 'post').mockRejectedValue(
      new AxiosError('bad', 'ERR', config, null, {
        status: 400,
        statusText: '',
        headers: {},
        config,
        data: { code: 'VALIDATION_FAILED', fieldErrors: { adminEmail: 'must be a well-formed email address' } },
      }),
    )
    renderRoutes(routes, { path: '/register' })

    await fill()

    expect(await screen.findByText('must be a well-formed email address')).toBeInTheDocument()
  })
})

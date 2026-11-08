import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { defaultPolicySettings } from '@/test/fixtures'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'

const activeAdmin = () =>
  makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))

const v1 = {
  version: 1,
  saved: true,
  settings: defaultPolicySettings(),
  createdAt: '2026-11-08T10:00:00Z',
  createdByName: 'Asha Admin',
}

function httpError(status: number, data: object) {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('fail', 'ERR', config, null, { status, statusText: '', headers: {}, config, data })
}

function mockGets() {
  vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url === '/policy') return { data: v1 }
    if (url === '/policy/versions') return { data: [{ version: 1, createdAt: v1.createdAt, createdByName: 'Asha Admin' }] }
    if (url === '/policy/versions/1') return { data: v1 }
    throw new Error(`unexpected GET ${url}`)
  })
}

describe('PolicySettingsPage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('saves changes as a new version based on the loaded one', async () => {
    mockGets()
    const put = vi.spyOn(api, 'put').mockResolvedValue({ data: { ...v1, version: 2 } })
    renderRoutes(routes, { path: '/admin/settings/policy', auth: activeAdmin() })

    const field = await screen.findByLabelText('Students per invigilator')
    expect(screen.getByRole('button', { name: 'Save as new version' })).toBeDisabled()
    await userEvent.clear(field)
    await userEvent.type(field, '25')
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }))

    expect(await screen.findByText('Saved as version 2.')).toBeInTheDocument()
    const expected = defaultPolicySettings()
    expected.staffing.studentsPerInvigilator = 25
    expect(put).toHaveBeenCalledWith('/policy', { basedOnVersion: 1, settings: expected })
  })

  it('explains a concurrent change and offers to reload', async () => {
    mockGets()
    vi.spyOn(api, 'put').mockRejectedValue(
      httpError(409, { code: 'POLICY_CHANGED', detail: 'The policy was changed by someone else (now version 2).' }),
    )
    renderRoutes(routes, { path: '/admin/settings/policy', auth: activeAdmin() })

    await userEvent.click(await screen.findByLabelText('Visiting faculty are eligible for duties'))
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('changed by someone else')
    expect(screen.getByRole('button', { name: 'Load the latest version' })).toBeInTheDocument()
  })

  it('shows server validation next to the field', async () => {
    mockGets()
    vi.spyOn(api, 'put').mockRejectedValue(
      httpError(400, { code: 'VALIDATION_FAILED', fieldErrors: { 'settings.workload.minRestMinutes': 'must be less than or equal to 720' } }),
    )
    renderRoutes(routes, { path: '/admin/settings/policy', auth: activeAdmin() })

    await userEvent.click(await screen.findByLabelText('Visiting faculty are eligible for duties'))
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }))

    expect(await screen.findByText('must be less than or equal to 720')).toBeInTheDocument()
  })

  it('lists history and shows past versions read-only', async () => {
    mockGets()
    renderRoutes(routes, { path: '/admin/settings/policy', auth: activeAdmin() })

    await userEvent.click(await screen.findByRole('link', { name: 'View' }))

    expect(await screen.findByRole('heading', { name: 'Policy version 1' })).toBeInTheDocument()
    expect(screen.getByLabelText('Maximum duties per day')).toBeDisabled()
  })

  it('is not available to faculty', async () => {
    renderRoutes(routes, { path: '/admin/settings/policy', auth: makeAuth(makeSession(['FACULTY'])) })

    expect(await screen.findByRole('heading', { name: 'No access' })).toBeInTheDocument()
  })
})

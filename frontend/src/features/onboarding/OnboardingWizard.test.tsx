import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { sessionStore } from '@/features/auth/sessionStore'
import { api } from '@/lib/api/client'
import { defaultPolicySettings, defaultPolicyView, newCollege } from '@/test/fixtures'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'

function mockBackend() {
  let college = newCollege()
  const calls: string[] = []
  vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url === '/college') return { data: college }
    if (url === '/policy') return { data: defaultPolicyView() }
    if (url === '/auth/me') return { data: makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: college.status } }) }
    throw new Error(`unexpected GET ${url}`)
  })
  const put = vi.spyOn(api, 'put').mockImplementation(async (url: string, body: unknown) => {
    calls.push(`PUT ${url}`)
    if (url === '/college') {
      college = { ...college, profile: { ...college.profile, ...(body as object) }, missingProfileFields: [] }
      return { data: college }
    }
    if (url === '/policy') {
      const { settings } = body as { settings: unknown }
      return { data: { version: 1, saved: true, settings, createdAt: '2026-11-08T10:00:00Z', createdByName: 'Asha Admin' } }
    }
    throw new Error(`unexpected PUT ${url}`)
  })
  const post = vi.spyOn(api, 'post').mockImplementation(async (url: string) => {
    calls.push(`POST ${url}`)
    if (url === '/college/complete-onboarding') {
      college = { ...college, status: 'ACTIVE', onboardingCompletedAt: '2026-11-08T10:00:00Z' }
      return { data: college }
    }
    throw new Error(`unexpected POST ${url}`)
  })
  return { put, post, calls }
}

async function continueTo(heading: string) {
  await userEvent.click(screen.getByRole('button', { name: /continue/i }))
  return screen.findByRole('heading', { name: heading })
}

describe('OnboardingWizard', () => {
  afterEach(() => vi.restoreAllMocks())

  it('requires the institution details before moving on', async () => {
    const { put } = mockBackend()
    renderRoutes(routes, { path: '/admin/setup', auth: makeAuth() })

    await screen.findByRole('heading', { name: 'Institution' })
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

    expect(await screen.findByText('Affiliation is required')).toBeInTheDocument()
    expect(put).not.toHaveBeenCalled()
  })

  it('walks through every step, saves policy version 1 and activates the college', async () => {
    const { put, calls } = mockBackend()
    renderRoutes(routes, { path: '/admin/setup', auth: makeAuth() })

    await screen.findByRole('heading', { name: 'Institution' })
    await userEvent.type(screen.getByLabelText('Affiliation'), 'APJ Abdul Kalam Technological University')
    await userEvent.type(screen.getByLabelText('Address'), 'Ramavarmapuram')
    await userEvent.type(screen.getByLabelText('City'), 'Thrissur')
    await userEvent.type(screen.getByLabelText('State'), 'Kerala')
    await continueTo('Faculty and subject rules')

    await userEvent.click(screen.getByLabelText('Faculty may invigilate a subject they teach'))
    await continueTo('Workload')

    const maxPerDay = screen.getByLabelText('Maximum duties per day')
    await userEvent.clear(maxPerDay)
    await userEvent.type(maxPerDay, '9')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByText('At most 6')).toBeInTheDocument()
    await userEvent.clear(maxPerDay)
    await userEvent.type(maxPerDay, '3')
    await continueTo('Hall staffing')
    await continueTo('Availability and leave')
    await continueTo('Duty swaps')
    await continueTo('Review')

    expect(screen.getByText('Invigilate own subject: allowed')).toBeInTheDocument()
    expect(screen.getByText('At most 3 duties per day')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Save policy and finish setup' }))

    expect(await screen.findByRole('heading', { name: 'Examination cell' })).toBeInTheDocument()
    expect(calls).toEqual(['PUT /college', 'PUT /policy', 'POST /college/complete-onboarding'])
    const expected = defaultPolicySettings()
    expected.faculty.allowOwnSubject = true
    expected.workload.maxDutiesPerDay = 3
    expect(put).toHaveBeenCalledWith('/policy', { basedOnVersion: 0, settings: expected })
    expect(sessionStore.get()?.session.college.status).toBe('ACTIVE')
  })

  it('sends finished colleges back to the overview', async () => {
    mockBackend()
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/college') return { data: newCollege({ status: 'ACTIVE' }) }
      if (url === '/policy') return { data: defaultPolicyView() }
      throw new Error(url)
    })
    const { router } = renderRoutes(routes, {
      path: '/admin/setup',
      auth: makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } })),
    })

    await screen.findByRole('heading', { name: 'Examination cell' })
    expect(router.state.location.pathname).toBe('/admin')
  })
})

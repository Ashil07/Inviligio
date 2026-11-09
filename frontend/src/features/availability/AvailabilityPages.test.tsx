import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { defaultPolicySettings } from '@/test/fixtures'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import type { AvailabilityRequest } from './api'

const active = { ...makeSession().college, status: 'ACTIVE' as const }
const facultyLogin = () => makeAuth(makeSession(['FACULTY'], { facultyId: 'f1', college: active }))

const session = { id: 's1', date: '2027-03-02', label: 'FN', startTime: '09:30:00', endTime: '12:30:00' }

function request(overrides: Partial<AvailabilityRequest> = {}): AvailabilityRequest {
  return {
    id: 'r1',
    faculty: { id: 'f1', code: 'F1', name: 'Ravi Kumar' },
    departmentCode: 'CSE',
    fromDate: '2027-03-02',
    toDate: '2027-03-03',
    session: null,
    type: 'LEAVE',
    reason: 'Family function',
    status: 'PENDING',
    requestedByName: 'Ravi Kumar',
    reviewedByName: null,
    reviewedAt: null,
    reviewNote: null,
    createdAt: '2027-02-20T10:00:00Z',
    ...overrides,
  }
}

function mockGets(availability: Partial<ReturnType<typeof defaultPolicySettings>['availability']>, extra: Record<string, unknown> = {}) {
  const settings = defaultPolicySettings()
  settings.availability = { ...settings.availability, ...availability }
  return vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url in extra) return { data: extra[url] }
    if (url === '/policy') return { data: { version: 1, saved: true, settings, createdAt: null, createdByName: null } }
    if (url === '/me/availability') return { data: [] }
    if (url === '/exam-periods') return { data: [{ id: 'p1', name: 'Mar 2027', startDate: '2027-03-01', endDate: '2027-03-12', status: 'PLANNING', sessionCount: 1, version: 0 }] }
    if (url === '/exam-periods/p1/sessions') {
      return { data: [{ ...session, examCount: 1, hallCount: 1, students: 50, positions: 2, warningCount: 0, version: 0 }] }
    }
    throw new Error(`unexpected GET ${url}`)
  })
}

describe('Availability pages', () => {
  afterEach(() => vi.restoreAllMocks())

  it('faculty request whole days and are told it awaits approval', async () => {
    mockGets({ leaveRequiresHodApproval: true })
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: request() })
    renderRoutes(routes, { path: '/faculty/availability', auth: facultyLogin() })

    expect(await screen.findByText('Your head of department will review the request.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('First day'), '2027-03-02')
    await userEvent.clear(screen.getByLabelText('Last day'))
    await userEvent.type(screen.getByLabelText('Last day'), '2027-03-03')
    await userEvent.type(screen.getByLabelText('Reason'), 'Family function')
    await userEvent.click(screen.getByRole('button', { name: 'Send request' }))

    expect(await screen.findByText('Sent for approval.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/me/availability', { fromDate: '2027-03-02', toDate: '2027-03-03', type: 'LEAVE', reason: 'Family function' })
  })

  it('offers only single sessions when the policy says so', async () => {
    mockGets({ allowFullDayUnavailability: false, allowSessionUnavailability: true })
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: request({ status: 'APPROVED', session }) })
    renderRoutes(routes, { path: '/faculty/availability', auth: facultyLogin() })

    await screen.findByText('Requests take effect immediately.')
    expect(screen.queryByLabelText('First day')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('option', { name: 'Mar 2027' })).toBeInTheDocument())
    await userEvent.selectOptions(screen.getByLabelText('Examination period'), 'p1')
    await waitFor(() => expect(screen.getByRole('option', { name: /FN 09:30-12:30/ })).toBeInTheDocument())
    await userEvent.selectOptions(screen.getByLabelText('Session'), 's1')
    await userEvent.selectOptions(screen.getByLabelText('Type'), 'UNAVAILABLE')
    await userEvent.click(screen.getByRole('button', { name: 'Send request' }))

    expect(await screen.findByText('Saved. You will not be given duties then.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/me/availability', { sessionId: 's1', type: 'UNAVAILABLE', reason: undefined })
  })

  it('explains when a login has no faculty record', async () => {
    mockGets({})
    renderRoutes(routes, { path: '/faculty/availability', auth: makeAuth(makeSession(['FACULTY'], { college: active })) })

    expect(await screen.findByText(/not linked to a faculty record/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Send request' })).not.toBeInTheDocument()
  })

  it('lets a HOD approve with a note', async () => {
    mockGets({}, { '/availability': [request()] })
    vi.spyOn(window, 'prompt').mockReturnValue('Enjoy the function')
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: request({ status: 'APPROVED' }) })
    renderRoutes(routes, { path: '/hod/requests', auth: makeAuth(makeSession(['HOD', 'FACULTY'], { facultyId: 'h1', college: active })) })

    await userEvent.click(await screen.findByRole('button', { name: /Approve Ravi Kumar/ }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/availability/r1/approve', { note: 'Enjoy the function' }))
  })

  it('shows the exam cell who is unavailable in each session', async () => {
    mockGets({}, {
      '/availability': [],
      '/exam-periods/p1/availability': [
        {
          session,
          unavailable: [{ faculty: { id: 'f1', code: 'F1', name: 'Ravi Kumar' }, type: 'LEAVE', reason: null, wholeDay: true }],
          pending: [{ id: 'f2', code: 'F2', name: 'Meera Nair' }],
        },
      ],
      '/faculty': { items: [], page: 0, size: 20, total: 0 },
    })
    renderRoutes(routes, { path: '/admin/availability', auth: makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: active })) })

    const periodSelects = await screen.findAllByLabelText('Examination period')
    await waitFor(() => expect(screen.getAllByRole('option', { name: 'Mar 2027' }).length).toBeGreaterThan(0))
    await userEvent.selectOptions(periodSelects[periodSelects.length - 1]!, 'p1')

    expect(await screen.findByText('Ravi Kumar')).toBeInTheDocument()
    expect(screen.getByText('Meera Nair')).toBeInTheDocument()
  })
})

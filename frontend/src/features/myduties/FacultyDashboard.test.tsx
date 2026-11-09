import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import type { MyDuties, MyDuty } from './api'

const faculty = () =>
  makeAuth(makeSession(['FACULTY'], { college: { ...makeSession().college, status: 'ACTIVE' }, facultyId: 'f1', user: { id: 'u2', email: 'ravi@example.test', fullName: 'Ravi Kumar' } }))

function duty(overrides: Partial<MyDuty> = {}): MyDuty {
  return {
    assignmentId: 'a1',
    periodId: 'p1',
    periodName: 'Mar 2027',
    date: '2027-03-01',
    sessionLabel: 'FN',
    startTime: '09:30:00',
    endTime: '12:30:00',
    hallCode: 'H101',
    hallName: 'Main Block 101',
    building: 'Main Block',
    role: 'INVIGILATOR',
    status: 'ASSIGNED',
    colleagues: ['Meera Nair'],
    ...overrides,
  }
}

function mockApi(duties: MyDuties | AxiosError, requests: unknown[] = []) {
  return vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url === '/me/duties') {
      if (duties instanceof AxiosError) throw duties
      return { data: duties }
    }
    if (url === '/me/availability') return { data: requests }
    if (url === '/me/duties.ics') return { data: new Blob(['BEGIN:VCALENDAR']) }
    throw new Error(url)
  })
}

const mine = (overrides: Partial<MyDuties> = {}): MyDuties => ({
  timeZone: 'Asia/Kolkata',
  today: '2027-02-27',
  chiefDuties: 1,
  upcoming: [duty(), duty({ assignmentId: 'a2', date: '2027-03-03', sessionLabel: 'AN', startTime: '13:30:00', endTime: '16:30:00', hallCode: 'LAB1', hallName: 'Lab 1', building: null, role: 'CHIEF', colleagues: [] })],
  past: [duty({ assignmentId: 'a0', date: '2027-02-20', hallCode: 'H102', hallName: 'Main Block 102' })],
  ...overrides,
})

describe('FacultyDashboard', () => {
  afterEach(() => vi.restoreAllMocks())

  it('shows the next duty, counts, the calendar, both lists and pending leave', async () => {
    mockApi(mine(), [{ id: 'r1', status: 'PENDING' }, { id: 'r2', status: 'APPROVED' }])
    renderRoutes(routes, { path: '/faculty', auth: faculty() })

    const next = await screen.findByRole('region', { name: 'Next duty' })
    expect(next).toHaveTextContent('FN 09:30-12:30')
    expect(next).toHaveTextContent('Invigilator in H101 · Main Block 101, Main Block')
    expect(next).toHaveTextContent('With Meera Nair')
    expect(screen.getByText('Upcoming').nextSibling).toHaveTextContent('2')
    expect(screen.getByText('Done').nextSibling).toHaveTextContent('1')
    expect(screen.getByText('As chief').nextSibling).toHaveTextContent('1')

    // The calendar opens on the month of the next duty and marks each duty on its day.
    const grid = screen.getByRole('grid', { name: /March 2027/ })
    expect(within(grid).getByRole('gridcell', { name: '2027-03-01: FN H101' })).toBeInTheDocument()
    expect(within(grid).getByRole('gridcell', { name: '2027-03-03: AN LAB1' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Previous month' }))
    expect(screen.getByRole('grid', { name: /February 2027/ })).toBeInTheDocument()
    expect(screen.getByRole('gridcell', { name: '2027-02-20: FN H102' })).toBeInTheDocument()

    expect(screen.getByText('Chief invigilator')).toBeInTheDocument()
    expect(screen.getByText('H102 · Main Block 102, Main Block')).toBeInTheDocument()
    expect(await screen.findByText(/1 request is waiting for a decision/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Manage leave requests' })).toHaveAttribute('href', '/faculty/availability')
  })

  it('downloads the calendar file', async () => {
    const get = mockApi(mine())
    const createUrl = vi.fn(() => 'blob:duties')
    const revokeUrl = vi.fn()
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: createUrl, revokeObjectURL: revokeUrl }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderRoutes(routes, { path: '/faculty', auth: faculty() })

    await userEvent.click(await screen.findByRole('button', { name: /Download calendar/ }))

    await waitFor(() => expect(get).toHaveBeenCalledWith('/me/duties.ics', { responseType: 'blob' }))
    expect(createUrl).toHaveBeenCalled()
    expect(click).toHaveBeenCalled()
    expect(revokeUrl).toHaveBeenCalledWith('blob:duties')
  })

  it('explains when nothing has been published yet', async () => {
    mockApi(mine({ upcoming: [], past: [], chiefDuties: 0 }))
    renderRoutes(routes, { path: '/faculty', auth: faculty() })

    expect(await screen.findByText(/once the examination cell publishes a duty chart/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Download calendar/ })).toBeDisabled()
  })

  it('explains when the login is not linked to a faculty record', async () => {
    const config = { headers: new AxiosHeaders() }
    mockApi(new AxiosError('x', 'ERR', config, null, { status: 403, statusText: '', headers: {}, config, data: { code: 'NOT_A_FACULTY_MEMBER' } }))
    renderRoutes(routes, { path: '/faculty', auth: faculty() })

    expect(await screen.findByText(/not linked to a faculty record/)).toBeInTheDocument()
  })
})

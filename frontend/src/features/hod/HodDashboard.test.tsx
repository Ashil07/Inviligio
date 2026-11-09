import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders, type AxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import type { DepartmentOverview, MemberRow } from './api'

const hod = () => makeAuth(makeSession(['HOD', 'FACULTY'], { college: { ...makeSession().college, status: 'ACTIVE' }, facultyId: 'h1' }))

function member(overrides: Partial<MemberRow>): MemberRow {
  return { facultyId: 'f', code: 'F', name: 'Someone', designation: 'Professor', active: true, dutyWeight: 1, duties: 3, chiefDuties: 0, fairShare: 3, load: 'BALANCED', ...overrides }
}

const overview = (overrides: Partial<DepartmentOverview> = {}): DepartmentOverview => ({
  department: { id: 'd1', code: 'ECE', name: 'Electronics' },
  periods: [
    { id: 'p2', name: 'May 2027', startDate: '2027-05-01', endDate: '2027-05-10', publishedVersion: 1 },
    { id: 'p1', name: 'Mar 2027', startDate: '2027-03-01', endDate: '2027-03-10', publishedVersion: 2 },
  ],
  periodId: 'p2',
  periodName: 'May 2027',
  chartVersion: 1,
  publishedAt: '2027-04-20T10:00:00Z',
  departmentDuties: 9,
  collegeAverage: 3,
  departmentAverage: 3,
  faculty: [
    member({ facultyId: 'a', code: 'E1', name: 'Asha Rao', duties: 5, chiefDuties: 2, load: 'OVER' }),
    member({ facultyId: 'b', code: 'E2', name: 'Bala N', duties: 3 }),
    member({ facultyId: 'c', code: 'E3', name: 'Chitra S', duties: 1, load: 'UNDER' }),
    member({ facultyId: 'd', code: 'E4', name: 'Dev K', active: false, duties: 0, fairShare: 0, load: null }),
  ],
  duties: [
    { assignmentId: 'x1', date: '2027-05-01', sessionLabel: 'FN', startTime: '09:30:00', endTime: '12:30:00', hallCode: 'H101', hallName: 'Main 101', facultyId: 'a', facultyCode: 'E1', facultyName: 'Asha Rao', role: 'CHIEF' },
  ],
  ...overrides,
})

function mockApi(data: DepartmentOverview | AxiosError | ((periodId?: string) => DepartmentOverview), pending: unknown[] = []) {
  return vi.spyOn(api, 'get').mockImplementation(async (url: string, config?: AxiosRequestConfig) => {
    if (url === '/department/overview') {
      if (data instanceof AxiosError) throw data
      const periodId = (config?.params as { periodId?: string } | undefined)?.periodId
      return { data: typeof data === 'function' ? data(periodId) : data }
    }
    if (url === '/availability') return { data: pending }
    throw new Error(url)
  })
}

describe('HodDashboard', () => {
  afterEach(() => vi.restoreAllMocks())

  it('shows workload, counts, duties and leave waiting for a decision', async () => {
    mockApi(overview(), [
      { id: 'r1', faculty: { id: 'c', code: 'E3', name: 'Chitra S' }, fromDate: '2027-05-02', toDate: '2027-05-02', session: null, reason: 'Family function', status: 'PENDING' },
    ])
    renderRoutes(routes, { path: '/hod', auth: hod() })

    expect(await screen.findByText('Electronics (ECE)')).toBeInTheDocument()
    expect(screen.getByText(/May 2027: duty chart version 1/)).toBeInTheDocument()
    expect(screen.getByText('Duties in department').nextSibling).toHaveTextContent('9')
    expect(screen.getByText('Overloaded').nextSibling).toHaveTextContent('1')
    expect(screen.getByText('Underloaded').nextSibling).toHaveTextContent('1')

    const asha = screen.getByRole('row', { name: /Asha Rao \(E1\) Professor/ })
    expect(within(asha).getByText('5')).toBeInTheDocument()
    expect(within(asha).getByText('Over')).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /Chitra S \(E3\)/ })).getByText('Under')).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /Dev K/ })).getByText('Inactive')).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /H101.*Asha Rao.*Chief invigilator/ })).toBeInTheDocument()

    expect(await screen.findByText('1 request waiting.')).toBeInTheDocument()
    expect(screen.getByText(/Family function/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review leave requests' })).toHaveAttribute('href', '/hod/requests')
  })

  it('switches between published exam periods', async () => {
    const get = mockApi((periodId) => (periodId === 'p1' ? overview({ periodId: 'p1', periodName: 'Mar 2027', chartVersion: 2 }) : overview()))
    renderRoutes(routes, { path: '/hod', auth: hod() })

    await userEvent.selectOptions(await screen.findByLabelText('Exam period'), 'p1')

    expect(await screen.findByText(/Mar 2027: duty chart version 2/)).toBeInTheDocument()
    await waitFor(() => expect(get).toHaveBeenCalledWith('/department/overview', { params: { periodId: 'p1' } }))
  })

  it('lists the department before anything is published', async () => {
    mockApi(overview({ periods: [], periodId: null, periodName: null, chartVersion: null, publishedAt: null, duties: [] }))
    renderRoutes(routes, { path: '/hod', auth: hod() })

    expect(await screen.findByText(/No duty chart has been published yet/)).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /Asha Rao \(E1\)/ })).toBeInTheDocument()
    expect(screen.queryByText('Duties in department')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Exam period')).not.toBeInTheDocument()
  })

  it('explains when the login is not linked to a faculty record', async () => {
    const config = { headers: new AxiosHeaders() }
    mockApi(new AxiosError('x', 'ERR', config, null, { status: 403, statusText: '', headers: {}, config, data: { code: 'NO_DEPARTMENT_SCOPE' } }))
    renderRoutes(routes, { path: '/hod', auth: hod() })

    expect(await screen.findByText(/does not know your department/)).toBeInTheDocument()
  })
})

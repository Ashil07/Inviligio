import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import { distribution, type Fairness, type FacultyRow } from './api'

const admin = () => makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))

function person(overrides: Partial<FacultyRow>): FacultyRow {
  return { facultyId: 'f', code: 'F', name: 'Someone', departmentCode: 'ECE', active: true, dutyWeight: 1, duties: 2, chiefDuties: 0, fairShare: 2, load: 'BALANCED', allPeriods: 2, ...overrides }
}

const fairness = (overrides: Partial<Fairness> = {}): Fairness => ({
  periods: [
    { id: 'p2', name: 'May 2027', startDate: '2027-05-01', publishedVersion: 1 },
    { id: 'p1', name: 'Mar 2027', startDate: '2027-03-01', publishedVersion: 2 },
  ],
  periodId: 'p2',
  periodName: 'May 2027',
  chartVersion: 1,
  summary: { positions: 9, filled: 8, unfilled: 1, activeFaculty: 4, duties: 8, mean: 2, min: 0, max: 5, stdDev: 1.87, over: 1, under: 1, withoutDuty: 1 },
  faculty: [
    person({ facultyId: 'a', code: 'E1', name: 'Asha Rao', duties: 5, chiefDuties: 1, load: 'OVER', allPeriods: 9 }),
    person({ facultyId: 'b', code: 'E2', name: 'Bala N', duties: 2 }),
    person({ facultyId: 'c', code: 'C1', name: 'Chitra S', departmentCode: 'CSE', duties: 1 }),
    person({ facultyId: 'd', code: 'C2', name: 'Dev K', departmentCode: 'CSE', duties: 0, load: 'UNDER', allPeriods: 1 }),
    person({ facultyId: 'e', code: 'C3', name: 'Old Hand', departmentCode: 'CSE', active: false, duties: 0, fairShare: 0, load: null, allPeriods: 4 }),
  ],
  departments: [
    { code: 'ECE', name: 'Electronics', faculty: 2, duties: 7, perPerson: 3.5, min: 2, max: 5, over: 1, under: 0 },
    { code: 'CSE', name: 'Computer Science', faculty: 2, duties: 1, perPerson: 0.5, min: 0, max: 1, over: 0, under: 1 },
  ],
  history: [
    { periodId: 'p1', name: 'Mar 2027', startDate: '2027-03-01', positions: 10, filled: 10, mean: 2.5, stdDev: 0.5, min: 2, max: 3 },
    { periodId: 'p2', name: 'May 2027', startDate: '2027-05-01', positions: 9, filled: 8, mean: 2, stdDev: 1.87, min: 0, max: 5 },
  ],
  unfilled: [{ date: '2027-05-02', sessionLabel: 'AN', hallCode: 'H105', role: 'INVIGILATOR', missing: 1, eliminated: { UNAVAILABLE: 2, BUSY_IN_SESSION: 3 } }],
  ...overrides,
})

function mockApi(answer: (periodId?: string) => Fairness) {
  return vi.spyOn(api, 'get').mockImplementation(async (url: string, config?: { params?: unknown }) => {
    if (url === '/analytics/fairness') return { data: answer((config?.params as { periodId?: string } | undefined)?.periodId) }
    throw new Error(url)
  })
}

describe('FairnessPage', () => {
  // The route loads the page (and the charts library) on demand; load it once up front so the first
  // test does not spend its wait on the import.
  beforeAll(async () => {
    await import('./FairnessPage')
  })
  afterEach(() => vi.restoreAllMocks())

  it('shows the figures, departments, faculty, gaps and history of the latest published period', async () => {
    mockApi(() => fairness())
    renderRoutes(routes, { path: '/admin/analytics', auth: admin() })

    expect((await screen.findByText('Positions filled')).nextSibling).toHaveTextContent('8 of 9')
    expect(screen.getByText('Average duties').nextSibling).toHaveTextContent('2')
    expect(screen.getByText('per person, range 0-5')).toBeInTheDocument()
    expect(screen.getByText('1 of 4')).toBeInTheDocument()
    expect(screen.getByText(/1 person over and 1 person under an even share/)).toBeInTheDocument()

    expect(screen.getByRole('row', { name: /Electronics \(ECE\) 2 7 3.5 2-5 1 0/ })).toBeInTheDocument()
    const asha = screen.getByRole('row', { name: /Asha Rao \(E1\)/ })
    expect(within(asha).getByText('Over')).toBeInTheDocument()
    expect(within(asha).getByText('9')).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /Old Hand/ })).getByText('Inactive')).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /H105 1 invigilator 2 .* 3 /i })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /Mar 2027 .* 10 of 10 2.5 2-3 0.5/ })).toBeInTheDocument()
  })

  it('filters the faculty table by department and by uneven workload', async () => {
    mockApi(() => fairness())
    renderRoutes(routes, { path: '/admin/analytics', auth: admin() })

    await userEvent.selectOptions(await screen.findByLabelText('Department'), 'CSE')
    expect(screen.queryByRole('row', { name: /Asha Rao/ })).not.toBeInTheDocument()
    expect(screen.getByRole('row', { name: /Chitra S/ })).toBeInTheDocument()

    await userEvent.click(screen.getByLabelText('Only over or under'))
    expect(screen.queryByRole('row', { name: /Chitra S/ })).not.toBeInTheDocument()
    expect(screen.getByRole('row', { name: /Dev K/ })).toBeInTheDocument()
  })

  it('switches between published periods', async () => {
    const get = mockApi((periodId) => (periodId === 'p1' ? fairness({ periodId: 'p1', periodName: 'Mar 2027', summary: { ...fairness().summary!, filled: 10, positions: 10, unfilled: 0 } }) : fairness()))
    renderRoutes(routes, { path: '/admin/analytics', auth: admin() })

    await userEvent.selectOptions(await screen.findByLabelText('Exam period'), 'p1')

    await waitFor(() => expect(screen.getByText('Positions filled').nextSibling).toHaveTextContent('10 of 10'))
    await waitFor(() => expect(get).toHaveBeenCalledWith('/analytics/fairness', { params: { periodId: 'p1' } }))
  })

  it('explains that nothing is published yet', async () => {
    mockApi(() => fairness({ periods: [], periodId: null, periodName: null, chartVersion: null, summary: null, history: [], unfilled: [] }))
    renderRoutes(routes, { path: '/admin/analytics', auth: admin() })

    expect(await screen.findByText(/No duty chart has been published yet/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Exam period')).not.toBeInTheDocument()
  })

  it('counts active people per number of duties, from zero to the highest', () => {
    expect(distribution(fairness().faculty)).toEqual([
      { duties: 0, faculty: 1 },
      { duties: 1, faculty: 1 },
      { duties: 2, faculty: 1 },
      { duties: 3, faculty: 0 },
      { duties: 4, faculty: 0 },
      { duties: 5, faculty: 1 },
    ])
  })
})

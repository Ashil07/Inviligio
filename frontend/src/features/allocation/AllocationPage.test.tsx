import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import type { DutyChart } from './api'

const admin = () => makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))
const period = { id: 'p1', name: 'Mar 2027', startDate: '2027-03-01', endDate: '2027-03-05', status: 'ALLOCATING', sessionCount: 2, version: 0 }

function chart(overrides: Partial<DutyChart> = {}): DutyChart {
  return {
    id: 'c1',
    periodId: 'p1',
    periodName: 'Mar 2027',
    chartVersion: 1,
    status: 'DRAFT',
    generatedAt: '2027-02-20T10:00:00Z',
    policyVersion: 1,
    policyChanged: false,
    inputsChanged: false,
    stats: { positions: 3, filled: 2, faculty: 2, minDuties: 1, maxDuties: 1, meanDuties: 1, stdDevDuties: 0, elapsedMillis: 5 },
    sessions: [
      {
        id: 's1',
        date: '2027-03-01',
        label: 'FN',
        startTime: '09:30:00',
        endTime: '12:30:00',
        halls: [
          {
            sessionHallId: 'sh1',
            hallCode: 'H1',
            hallName: 'Hall 1',
            required: 3,
            missing: 1,
            duties: [
              {
                assignmentId: 'a1',
                facultyId: 'f1',
                facultyCode: 'F1',
                facultyName: 'Ravi Kumar',
                departmentCode: 'ECE',
                role: 'INVIGILATOR',
                source: 'AUTO',
                explanation: { eligibleCandidates: 4, dutiesBefore: 0, penalties: ['invigilates an exam of their own department'] },
              },
            ],
          },
        ],
      },
    ],
    unfilled: [
      {
        sessionId: 's1',
        date: '2027-03-01',
        sessionLabel: 'FN',
        sessionHallId: 'sh1',
        hallCode: 'H1',
        role: 'INVIGILATOR',
        missing: 1,
        reason: {
          totalFaculty: 6,
          eliminated: { UNAVAILABLE: 4, OWN_SUBJECT: 1 },
          suggestions: ['1 faculty are blocked only because they teach a subject examined in this hall; relaxing that rule in the policy would make them eligible.'],
        },
      },
    ],
    workload: [
      { facultyId: 'f1', code: 'F1', name: 'Ravi Kumar', departmentCode: 'ECE', duties: 1, chiefDuties: 0, dutyWeight: 1 },
      { facultyId: 'f2', code: 'F2', name: 'Meera Nair', departmentCode: 'CSE', duties: 0, chiefDuties: 0, dutyWeight: 0.5 },
    ],
    ...overrides,
  }
}

function noChart() {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('x', 'ERR', config, null, { status: 404, statusText: '', headers: {}, config, data: { code: 'NO_CHART' } })
}

function mockGets(current: DutyChart | null) {
  vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url === '/exam-periods/p1') return { data: period }
    if (url === '/exam-periods/p1/duty-chart') {
      if (!current) throw noChart()
      return { data: current }
    }
    throw new Error(url)
  })
}

describe('AllocationPage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('generates the first chart and shows gaps, duties and workload', async () => {
    mockGets(null)
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: chart() })
    renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

    expect(await screen.findByText(/No duty chart yet/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Generate duty chart' }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/exam-periods/p1/duty-chart/generate'))
    expect(await screen.findByText('2 of 3')).toBeInTheDocument()
    expect(screen.getByText(/Of 6 faculty: 4 on leave or unavailable, 1 teach a subject examined here/)).toBeInTheDocument()
    expect(screen.getByText(/relaxing that rule/)).toBeInTheDocument()
    expect(screen.getByText('1 open')).toBeInTheDocument()

    await userEvent.click(screen.getAllByText('Ravi Kumar')[0]!)
    expect(screen.getByText(/Chosen from 4 eligible faculty; had 0 duty\(ies\).*own department/)).toBeVisible()
    expect(screen.getByText('Meera Nair')).toBeInTheDocument()
  })

  it('warns that a draft is out of date and confirms before replacing it', async () => {
    mockGets(chart({ inputsChanged: true, policyChanged: true }))
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const post = vi.spyOn(api, 'post')
    renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

    expect(await screen.findByText(/changed after this draft was generated/)).toBeInTheDocument()
    expect(screen.getByText(/newer version than the one this draft used/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Generate again' }))

    expect(window.confirm).toHaveBeenCalled()
    expect(post).not.toHaveBeenCalled()
  })

  it('shows why generation was refused', async () => {
    mockGets(null)
    const config = { headers: new AxiosHeaders() }
    vi.spyOn(api, 'post').mockRejectedValue(new AxiosError('x', 'ERR', config, null, {
      status: 422, statusText: '', headers: {}, config,
      data: { code: 'NOTHING_TO_ALLOCATE', detail: 'No session of this period has halls with students yet' },
    }))
    renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: 'Generate duty chart' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('No session of this period has halls with students yet')
  })
})

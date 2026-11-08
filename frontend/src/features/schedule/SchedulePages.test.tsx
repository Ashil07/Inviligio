import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import type { SessionDetail } from './api'

const admin = () => makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))
const period = { id: 'p1', name: 'Nov 2026', startDate: '2026-11-16', endDate: '2026-11-27', status: 'PLANNING', sessionCount: 0, version: 0 }

function sessionDetail(overrides: Partial<SessionDetail> = {}): SessionDetail {
  return {
    id: 's1',
    periodId: 'p1',
    periodName: 'Nov 2026',
    date: '2026-11-16',
    label: 'FN',
    startTime: '09:30:00',
    endTime: '12:30:00',
    exams: [
      {
        id: 'e1',
        subject: { id: 'sub1', code: 'CS301', name: 'Algorithms' },
        department: { id: 'd1', code: 'CSE', name: 'CS' },
        type: 'THEORY',
        registeredStudents: 70,
        seatedStudents: 0,
        notes: null,
        version: 0,
      },
    ],
    halls: [],
    totalStudents: 0,
    totalPositions: 0,
    warnings: ['CS301 has no students seated in any hall', 'No halls are assigned to this session yet'],
    version: 0,
    ...overrides,
  }
}

function httpError(status: number, data: object) {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('x', 'ERR', config, null, { status, statusText: '', headers: {}, config, data })
}

describe('Examination scheduling pages', () => {
  afterEach(() => vi.restoreAllMocks())

  it('creates an examination period', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: [] })
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: period })
    renderRoutes(routes, { path: '/admin/exams', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: 'New examination period' }))
    await userEvent.type(screen.getByLabelText('Name'), 'Nov 2026')
    await userEvent.type(screen.getByLabelText('First day'), '2026-11-27')
    await userEvent.type(screen.getByLabelText('Last day'), '2026-11-16')
    await userEvent.click(screen.getByRole('button', { name: 'Create period' }))
    expect(await screen.findByText('Ends before it starts')).toBeInTheDocument()

    await userEvent.clear(screen.getByLabelText('First day'))
    await userEvent.type(screen.getByLabelText('First day'), '2026-11-16')
    await userEvent.clear(screen.getByLabelText('Last day'))
    await userEvent.type(screen.getByLabelText('Last day'), '2026-11-27')
    await userEvent.click(screen.getByRole('button', { name: 'Create period' }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/exam-periods', { name: 'Nov 2026', startDate: '2026-11-16', endDate: '2026-11-27' }))
  })

  it('generates daily sessions for the period, skipping Sundays', async () => {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url === '/exam-periods/p1') return { data: period }
      if (url === '/exam-periods/p1/sessions') return { data: [] }
      throw new Error(url)
    })
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { created: 20, skipped: 0 } })
    renderRoutes(routes, { path: '/admin/exams/p1', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: 'Generate sessions' }))
    await userEvent.click(screen.getByRole('button', { name: 'Generate' }))

    expect(await screen.findByText('Created 20 sessions.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/exam-periods/p1/sessions/bulk', {
      fromDate: '2026-11-16',
      toDate: '2026-11-27',
      skipDays: ['SUNDAY'],
      slots: [
        { label: 'FN', startTime: '09:30', endTime: '12:30' },
        { label: 'AN', startTime: '13:30', endTime: '16:30' },
      ],
    })
  })

  it('seats students in a hall and shows the invigilators needed', async () => {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url === '/exam-sessions/s1') return { data: sessionDetail() }
      if (url === '/halls') return { data: [{ id: 'h1', code: 'H1', name: 'Hall 1', building: null, capacity: 60, minInvigilators: 1, lab: false, active: true, version: 0 }] }
      if (url === '/subjects') return { data: { items: [], page: 0, size: 20, total: 0 } }
      throw new Error(url)
    })
    const seated = sessionDetail({
      halls: [
        {
          id: 'sh1',
          hall: { id: 'h1', code: 'H1', name: 'Hall 1', capacity: 60, minInvigilators: 1, lab: false },
          seating: [{ examId: 'e1', subjectCode: 'CS301', studentCount: 55 }],
          totalStudents: 55,
          staffing: { invigilators: 2, chief: 0, total: 2, basis: 'Largest of college minimum 1, hall minimum 1 and 55 students / 30 = 2' },
          requiredOverride: null,
          overrideReason: null,
          version: 0,
        },
      ],
      totalStudents: 55,
      totalPositions: 2,
      warnings: ['CS301: 15 of 70 registered students are not seated'],
    })
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: seated })
    renderRoutes(routes, { path: '/admin/exams/sessions/s1', auth: admin() })

    expect(await screen.findByText('No halls are assigned to this session yet')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Add hall' }))
    const panel = screen.getByRole('region', { name: 'Add hall' })
    await waitFor(() => expect(within(panel).getByRole('option', { name: /H1 - Hall 1/ })).toBeInTheDocument())
    await userEvent.selectOptions(within(panel).getByLabelText('Hall'), 'h1')
    await userEvent.type(within(panel).getByLabelText('CS301 Algorithms'), '55')
    expect(within(panel).getByText('Total 55 of 60 seats')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: 'Add hall' }))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/exam-sessions/s1/halls', {
        hallId: 'h1',
        requiredOverride: null,
        overrideReason: null,
        seating: [{ examId: 'e1', studentCount: 55 }],
      }),
    )
    expect(await screen.findByText(/55 students \/ 30 = 2/)).toBeInTheDocument()
    expect(screen.getByText('CS301: 15 of 70 registered students are not seated')).toBeInTheDocument()
  })

  it('explains a hall clash', async () => {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url === '/exam-sessions/s1') return { data: sessionDetail() }
      if (url === '/halls') return { data: [{ id: 'h1', code: 'H1', name: 'Hall 1', building: null, capacity: 60, minInvigilators: 1, lab: false, active: true, version: 0 }] }
      if (url === '/subjects') return { data: { items: [], page: 0, size: 20, total: 0 } }
      throw new Error(url)
    })
    vi.spyOn(api, 'post').mockRejectedValue(httpError(409, { code: 'HALL_BUSY', detail: 'Hall H1 is already used by the PR session (11:00-14:00) on 2026-11-16' }))
    renderRoutes(routes, { path: '/admin/exams/sessions/s1', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: 'Add hall' }))
    const panel = screen.getByRole('region', { name: 'Add hall' })
    await waitFor(() => expect(within(panel).getByRole('option', { name: /H1/ })).toBeInTheDocument())
    await userEvent.selectOptions(within(panel).getByLabelText('Hall'), 'h1')
    await userEvent.click(within(panel).getByRole('button', { name: 'Add hall' }))

    expect(await within(panel).findByText(/already used by the PR session/)).toBeInTheDocument()
  })
})

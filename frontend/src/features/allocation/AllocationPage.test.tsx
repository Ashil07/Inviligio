import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import type { DutyChart, HistoryEntry, SubstituteOptions, Suggestion, Validation, VersionSummary } from './api'

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
                version: 3,
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
      { facultyId: 'f1', code: 'F1', name: 'Ravi Kumar', departmentCode: 'ECE', duties: 1, chiefDuties: 0, dutyWeight: 1, active: true },
      { facultyId: 'f2', code: 'F2', name: 'Meera Nair', departmentCode: 'CSE', duties: 0, chiefDuties: 0, dutyWeight: 0.5, active: true },
    ],
    issues: [],
    version: 7,
    publishedAt: null,
    publishedBy: null,
    publishedVersion: null,
    changesNotInDraft: 0,
    ...overrides,
  }
}

function noChart() {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('x', 'ERR', config, null, { status: 404, statusText: '', headers: {}, config, data: { code: 'NO_CHART' } })
}

function mockGets(current: DutyChart | null, history: HistoryEntry[] = [], versions: VersionSummary[] = []) {
  vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url === '/exam-periods/p1') return { data: period }
    if (url === '/exam-periods/p1/duty-chart') {
      if (!current) throw noChart()
      return { data: current }
    }
    if (url === '/exam-periods/p1/duty-chart/history') return { data: history }
    if (url === '/exam-periods/p1/duty-chart/versions') return { data: versions }
    throw new Error(url)
  })
}

const meera: Suggestion = { facultyId: 'f2', code: 'F2', name: 'Meera Nair', departmentCode: 'CSE', duties: 0, warnings: [] }

/** Answers validation requests: suggestions without a faculty id, `verdict` with one. */
function mockValidation(verdict: Partial<Validation>) {
  return vi.spyOn(api, 'post').mockImplementation(async (url: string, body?: unknown) => {
    if (url !== '/exam-periods/p1/duty-chart/assignments/validate') throw new Error(url)
    const request = body as { facultyId?: string }
    if (!request.facultyId) return { data: { allowed: true, blocking: [], warnings: [], alternatives: [meera] } }
    return { data: { allowed: true, blocking: [], warnings: [], alternatives: [meera], ...verdict } }
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

  describe('publishing', () => {
    it('publishes the reviewed draft after confirming open positions and the version it replaces', async () => {
      mockGets(chart({ publishedVersion: 1, chartVersion: 2 }))
      vi.spyOn(window, 'confirm').mockReturnValue(true)
      const post = vi.spyOn(api, 'post').mockResolvedValue({ data: chart({ status: 'PUBLISHED', chartVersion: 2, publishedVersion: 2, publishedAt: '2027-02-21T09:00:00Z' }) })
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      expect(await screen.findByText('No duty breaks a strict rule.')).toBeInTheDocument()
      expect(screen.getByText(/1 position is still open/)).toBeInTheDocument()
      expect(screen.getByText(/Version 1 is published and stays in force/)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Publish version 2' }))

      expect(window.confirm).toHaveBeenCalledWith(expect.stringMatching(/replaces published version 1[\s\S]*1 position is still open/))
      await waitFor(() => expect(post).toHaveBeenCalledWith('/exam-periods/p1/duty-chart/publish', { version: 7 }))
      expect(await screen.findByText(/this is what faculty see/)).toBeInTheDocument()
    })

    it('warns that publishing a draft undoes swaps made on the published version', async () => {
      mockGets(chart({ publishedVersion: 1, chartVersion: 2, changesNotInDraft: 2 }))
      vi.spyOn(window, 'confirm').mockReturnValue(false)
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      expect(await screen.findByText(/2 swaps or substitutes were made on published version 1 after this draft was started/)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Publish version 2' }))
      expect(window.confirm).toHaveBeenCalledWith(expect.stringMatching(/2 swaps or substitutes made since this draft was started will be undone/))
    })

    it('does not allow publishing while a duty breaks a strict rule', async () => {
      mockGets(chart({ issues: [{ assignmentId: 'a1', date: '2027-03-01', sessionLabel: 'FN', hallCode: 'H1', facultyName: 'Ravi Kumar', reasons: ['UNAVAILABLE'] }] }))
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      expect(await screen.findByText(/1 duty breaks a strict rule; fix it before publishing/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Publish version 1' })).toBeDisabled()
    })

    it('starts a new version from the published chart and lists versions', async () => {
      mockGets(
        chart({ status: 'PUBLISHED', publishedVersion: 1, publishedAt: '2027-02-21T09:00:00Z', publishedBy: 'Asha Admin' }),
        [],
        [{ chartVersion: 1, status: 'PUBLISHED', createdAt: '2027-02-20T10:00:00Z', publishedAt: '2027-02-21T09:00:00Z', publishedBy: 'Asha Admin', positions: 3, filled: 2 }],
      )
      const post = vi.spyOn(api, 'post').mockResolvedValue({ data: chart({ chartVersion: 2, publishedVersion: 1 }) })
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      expect(await screen.findByText(/by Asha Admin; this is what faculty see/)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Replace Ravi Kumar/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Publish version/ })).not.toBeInTheDocument()
      expect(await screen.findByRole('link', { name: 'View' })).toHaveAttribute('href', '/admin/exams/p1/allocation/versions/1')
      expect(screen.getAllByText('2 of 3')).toHaveLength(2)

      await userEvent.click(screen.getByRole('button', { name: 'Start new version' }))
      await waitFor(() => expect(post).toHaveBeenCalledWith('/exam-periods/p1/duty-chart/draft'))
      expect(await screen.findByRole('button', { name: 'Replace Ravi Kumar in H1' })).toBeInTheDocument()
    })
  })

  describe('editing the draft', () => {
    it('replaces a duty with a suggested person after the server checks the choice', async () => {
      mockGets(chart())
      const post = mockValidation({ warnings: ['would have 3 duties in this period; the average is 1.2'] })
      const put = vi.spyOn(api, 'put').mockResolvedValue({ data: chart() })
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      await userEvent.click(await screen.findByRole('button', { name: 'Replace Ravi Kumar in H1' }))
      expect(screen.getByRole('region', { name: 'Replace Ravi Kumar' })).toHaveTextContent('invigilator in H1')
      await userEvent.click(await screen.findByRole('button', { name: 'Choose Meera Nair' }))

      expect(await screen.findByText('Allowed, but note')).toBeInTheDocument()
      expect(screen.getByText(/would have 3 duties/)).toBeInTheDocument()
      expect(post).toHaveBeenCalledWith('/exam-periods/p1/duty-chart/assignments/validate', {
        sessionHallId: 'sh1', role: 'INVIGILATOR', replacesAssignmentId: 'a1', facultyId: 'f2',
      })
      await userEvent.type(screen.getByLabelText('Reason'), 'Clash with lab')
      await userEvent.click(screen.getByRole('button', { name: 'Replace' }))

      await waitFor(() => expect(put).toHaveBeenCalledWith('/exam-periods/p1/duty-chart/assignments/a1', {
        facultyId: 'f2', version: 3, reason: 'Clash with lab',
      }))
      await waitFor(() => expect(screen.queryByRole('region', { name: 'Replace Ravi Kumar' })).not.toBeInTheDocument())
    })

    it('blocks a choice that breaks a strict rule', async () => {
      mockGets(chart())
      mockValidation({ allowed: false, blocking: [{ code: 'BUSY_IN_SESSION', message: 'Meera Nair is already on duty at this time.' }] })
      const put = vi.spyOn(api, 'put')
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      await userEvent.click(await screen.findByRole('button', { name: 'Replace Ravi Kumar in H1' }))
      await userEvent.selectOptions(screen.getByLabelText('Faculty member'), 'f2')

      expect(await screen.findByText('Meera Nair is already on duty at this time.')).toBeInTheDocument()
      expect(screen.getByText('Not allowed')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Replace' })).toBeDisabled()
      expect(put).not.toHaveBeenCalled()
    })

    it('fills an open position', async () => {
      mockGets(chart())
      const post = mockValidation({})
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      await userEvent.click(await screen.findByRole('button', { name: 'Fill position in H1' }))
      await userEvent.click(await screen.findByRole('button', { name: 'Choose Meera Nair' }))
      expect(await screen.findByText('Fits every rule.')).toBeInTheDocument()
      post.mockResolvedValueOnce({ data: chart({ unfilled: [] }) })
      await userEvent.click(screen.getByRole('button', { name: 'Assign' }))

      await waitFor(() => expect(post).toHaveBeenCalledWith('/exam-periods/p1/duty-chart/assignments', {
        sessionHallId: 'sh1', role: 'INVIGILATOR', facultyId: 'f2', reason: '',
      }))
    })

    it('removes a duty, and lists rule problems and the edit history', async () => {
      mockGets(
        chart({ issues: [{ assignmentId: 'a1', date: '2027-03-01', sessionLabel: 'FN', hallCode: 'H1', facultyName: 'Ravi Kumar', reasons: ['UNAVAILABLE'] }] }),
        [{ id: 'h1', occurredAt: '2027-02-20T10:05:00Z', action: 'DUTY_REPLACED', actorName: 'Asha Admin',
          summary: 'Replaced Meera Nair (F2) with Ravi Kumar (F1) as invigilator in H1, Mon 1 Mar FN', reason: 'Clash with lab' }],
      )
      const remove = vi.spyOn(api, 'delete').mockResolvedValue({ data: chart() })
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      expect(await screen.findByText(/1 duty breaks a strict rule now/)).toBeInTheDocument()
      expect(screen.getByText(/Ravi Kumar \(on leave or unavailable\)/)).toBeInTheDocument()
      expect(await screen.findByText(/Replaced Meera Nair \(F2\) with Ravi Kumar/)).toBeInTheDocument()
      expect(screen.getByText(/Asha Admin · Reason: Clash with lab/)).toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Remove Ravi Kumar from H1' }))
      await userEvent.click(screen.getByRole('button', { name: 'Remove duty' }))

      await waitFor(() => expect(remove).toHaveBeenCalledWith('/exam-periods/p1/duty-chart/assignments/a1', {
        params: { version: 3, reason: undefined },
      }))
    })

    it('offers no editing on a published chart', async () => {
      mockGets(chart({ status: 'PUBLISHED' }))
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      expect(await screen.findByText('1 open')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Replace Ravi Kumar/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Fill/ })).not.toBeInTheDocument()
      // Without the policy allowing direct reassignment there are no substitutes either.
      expect(screen.queryByRole('button', { name: /Substitute for/ })).not.toBeInTheDocument()
    })
  })

  describe('substitutes', () => {
    const options: SubstituteOptions = {
      duty: { assignmentId: 'a1', date: '2027-03-01', sessionLabel: 'FN', startTime: '09:30:00', endTime: '12:30:00', hallCode: 'H1', hallName: 'Hall 1', role: 'INVIGILATOR', facultyId: 'f1', facultyName: 'Ravi Kumar', version: 3 },
      allowed: true,
      candidates: [
        { facultyId: 'f2', code: 'F2', name: 'Meera Nair', departmentCode: 'CSE', duties: 1, notes: [] },
        { facultyId: 'f3', code: 'F3', name: 'Arun Das', departmentCode: 'ECE', duties: 2, notes: ['invigilates an exam of their own department'] },
      ],
      excluded: { totalFaculty: 6, eliminated: { UNAVAILABLE: 1, BUSY_IN_SESSION: 2 }, suggestions: [] },
    }

    function mockPublished(allowDirect = true) {
      const published = chart({ status: 'PUBLISHED', publishedAt: '2027-02-21T09:00:00Z' })
      vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
        if (url === '/exam-periods/p1') return { data: period }
        if (url === '/exam-periods/p1/duty-chart') return { data: published }
        if (url === '/exam-periods/p1/duty-chart/history' || url === '/exam-periods/p1/duty-chart/versions') return { data: [] }
        if (url === '/policy') return { data: { version: 1, saved: true, settings: { swaps: { swapsEnabled: true, recipientMustAccept: true, hodApprovalRequired: false, adminCanReassignDirectly: allowDirect } } } }
        if (url === '/exam-periods/p1/duty-chart/assignments/a1/substitute') return { data: options }
        throw new Error(url)
      })
      return vi.spyOn(api, 'post').mockResolvedValue({ data: published })
    }

    it('gives a duty of the published chart to a ranked substitute with a reason', async () => {
      const post = mockPublished()
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      await userEvent.click(await screen.findByRole('button', { name: 'Substitute for Ravi Kumar in H1' }))
      const panel = await screen.findByRole('region', { name: 'Substitute for Ravi Kumar' })
      expect(within(panel).getByText(/best fit/)).toBeInTheDocument()
      expect(within(panel).getByText('invigilates an exam of their own department')).toBeInTheDocument()
      expect(within(panel).getByText(/Of 6 faculty:/)).toBeInTheDocument()

      // A reason is required.
      await userEvent.click(within(panel).getByRole('radio', { name: /Meera Nair/ }))
      await userEvent.click(within(panel).getByRole('button', { name: 'Give the duty to the substitute' }))
      expect(await within(panel).findByText(/Say why/)).toBeInTheDocument()
      expect(post).not.toHaveBeenCalled()

      await userEvent.type(within(panel).getByLabelText('Reason'), 'absent: fever')
      await userEvent.click(within(panel).getByRole('button', { name: 'Give the duty to the substitute' }))

      await waitFor(() =>
        expect(post).toHaveBeenCalledWith('/exam-periods/p1/duty-chart/assignments/a1/substitute', { facultyId: 'f2', version: 3, reason: 'absent: fever' }),
      )
      expect(await screen.findByText(/Meera Nair now has the duty of Ravi Kumar/)).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Substitute for Ravi Kumar' })).not.toBeInTheDocument()
    })

    it('offers no substitutes when the policy does not allow direct reassignment', async () => {
      mockPublished(false)
      renderRoutes(routes, { path: '/admin/exams/p1/allocation', auth: admin() })

      expect(await screen.findByText('1 open')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Substitute for/ })).not.toBeInTheDocument()
    })
  })
})

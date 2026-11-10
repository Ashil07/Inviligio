import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import { filenameFrom, reportParams } from './api'

const admin = () => makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))

function mockApi(report: (url: string, params: unknown) => unknown = () => ({
  data: new Blob(['%PDF']),
  headers: { 'content-disposition': 'attachment; filename="duty-chart-mar-2027-by-faculty.pdf"' },
})) {
  return vi.spyOn(api, 'get').mockImplementation(async (url: string, config?: { params?: unknown }) => {
    if (url === '/exam-periods') return { data: [{ id: 'p1', name: 'Mar 2027', startDate: '2027-03-01', endDate: '2027-03-05', status: 'PUBLISHED', sessionCount: 2, version: 1 }] }
    if (url === '/departments') return { data: [{ id: 'd1', code: 'ECE', name: 'Electronics', active: true, version: 0 }, { id: 'd2', code: 'CSE', name: 'Computer Science', active: true, version: 0 }] }
    if (url === '/halls') return { data: [{ id: 'h1', code: 'H101', name: 'Main 101', building: null, capacity: 60 }] }
    if (url === '/exam-periods/p1/duty-chart/versions') return { data: [{ chartVersion: 2, status: 'PUBLISHED' }, { chartVersion: 3, status: 'DRAFT' }] }
    if (url.startsWith('/exam-periods/p1/reports/')) return report(url, config?.params)
    throw new Error(url)
  })
}

/** Picks the period once the list has loaded. */
async function choosePeriod() {
  await screen.findByRole('option', { name: 'Mar 2027' })
  await userEvent.selectOptions(screen.getByLabelText('Exam period'), 'p1')
}

describe('ReportsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:report'), revokeObjectURL: vi.fn() }))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })
  afterEach(() => vi.restoreAllMocks())

  it('downloads the duty chart grouped and filtered as chosen', async () => {
    const get = mockApi()
    renderRoutes(routes, { path: '/admin/reports', auth: admin() })

    await choosePeriod()
    await userEvent.selectOptions(screen.getByLabelText('Group by'), 'faculty')
    await userEvent.selectOptions(await screen.findByLabelText('Chart version'), '3')
    await userEvent.selectOptions(screen.getByLabelText('Department'), 'ECE')
    await userEvent.type(screen.getByLabelText('Session'), 'AN')
    await userEvent.click(screen.getByRole('button', { name: /Download report/ }))

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/exam-periods/p1/reports/duty-chart', {
        params: { format: 'pdf', groupBy: 'faculty', version: 3, department: 'ECE', session: 'AN' },
        responseType: 'blob',
      }),
    )
    expect(await screen.findByText('Downloaded duty-chart-mar-2027-by-faculty.pdf.')).toBeInTheDocument()
  })

  it('offers only the filters each report uses', async () => {
    const get = mockApi(() => ({ data: new Blob(['a,b']), headers: { 'content-disposition': 'attachment; filename="workload-mar-2027.csv"' } }))
    renderRoutes(routes, { path: '/admin/reports', auth: admin() })

    await userEvent.click(await screen.findByRole('radio', { name: /Workload/ }))
    expect(screen.queryByLabelText('Group by')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Hall')).not.toBeInTheDocument()
    await choosePeriod()
    await userEvent.selectOptions(screen.getByLabelText('Format'), 'csv')
    await userEvent.selectOptions(screen.getByLabelText('Department'), 'CSE')
    await userEvent.click(screen.getByRole('button', { name: /Download report/ }))
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/exam-periods/p1/reports/workload', { params: { format: 'csv', department: 'CSE' }, responseType: 'blob' }),
    )

    await userEvent.click(screen.getByRole('radio', { name: /Fairness/ }))
    expect(screen.queryByLabelText('Department')).not.toBeInTheDocument()
  })

  it('shows why a report could not be made', async () => {
    const config = { headers: new AxiosHeaders() }
    const problem = new Blob([JSON.stringify({ code: 'NO_PUBLISHED_CHART', detail: 'No duty chart is published for this period yet; choose a version' })])
    mockApi(() => {
      throw new AxiosError('x', 'ERR', config, null, { status: 409, statusText: '', headers: {}, config, data: problem })
    })
    renderRoutes(routes, { path: '/admin/reports', auth: admin() })

    await choosePeriod()
    await userEvent.click(screen.getByRole('button', { name: /Download report/ }))

    expect(await screen.findByText(/No duty chart is published for this period yet/)).toBeInTheDocument()
  })

  it('checks the period and the date range before asking', async () => {
    const get = mockApi()
    renderRoutes(routes, { path: '/admin/reports', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: /Download report/ }))
    expect(await screen.findByText('Choose an exam period')).toBeInTheDocument()
    await choosePeriod()
    await userEvent.type(screen.getByLabelText('From'), '2027-03-05')
    await userEvent.type(screen.getByLabelText('To'), '2027-03-01')
    await userEvent.click(screen.getByRole('button', { name: /Download report/ }))
    expect(await screen.findByText('The end date is before the start date')).toBeInTheDocument()
    expect(get).not.toHaveBeenCalledWith(expect.stringContaining('/reports/'), expect.anything())
  })

  it('builds parameters and reads the file name', () => {
    expect(reportParams({ periodId: 'p1', type: 'fairness', format: 'xlsx', groupBy: 'hall', department: 'ECE' })).toEqual({ format: 'xlsx' })
    expect(reportParams({ periodId: 'p1', type: 'duty-chart', format: 'csv', groupBy: 'date', from: '2027-03-01', to: '', hall: 'H1' }))
      .toEqual({ format: 'csv', groupBy: 'date', from: '2027-03-01', hall: 'H1' })
    expect(filenameFrom('attachment; filename="fairness-mar-2027.pdf"', 'x')).toBe('fairness-mar-2027.pdf')
    expect(filenameFrom(undefined, 'workload.csv')).toBe('workload.csv')
  })
})

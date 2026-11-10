import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import { money, type Pay, type Rate } from './api'

const admin = () => makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))

const rate: Rate = { id: 'r1', role: 'INVIGILATOR', amount: 500, currency: 'INR', effectiveFrom: '2025-01-01', createdByName: 'Exam Cell', createdAt: '2025-01-01T00:00:00Z', version: 0 }

const pay = (overrides: Partial<Pay> = {}): Pay => ({
  periods: [{ id: 'p1', name: 'Jan 2026', startDate: '2026-01-05', publishedVersion: 1 }],
  periodId: 'p1',
  periodName: 'Jan 2026',
  chartVersion: 1,
  summary: { currency: 'INR', total: 1500, completed: 3, absent: 1, upcoming: 2, withoutRate: 1 },
  faculty: [
    { facultyId: 'a', code: 'E1', name: 'Asha Rao', departmentCode: 'ECE', completed: 2, chiefCompleted: 0, absent: 0, upcoming: 1, withoutRate: 1, amount: 1000 },
    { facultyId: 'b', code: 'C1', name: 'Bala N', departmentCode: 'CSE', completed: 1, chiefCompleted: 0, absent: 1, upcoming: 1, withoutRate: 0, amount: 500 },
  ],
  departments: [
    { code: 'ECE', name: 'Electronics', faculty: 1, completed: 2, amount: 1000 },
    { code: 'CSE', name: 'Computer Science', faculty: 1, completed: 1, amount: 500 },
  ],
  ...overrides,
})

function mockApi(answer: Pay = pay(), rates: Rate[] = [rate]) {
  const get = vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url === '/remuneration/rates') return { data: rates }
    if (url === '/remuneration') return { data: answer }
    if (url === '/exam-periods/p1/reports/remuneration') return { data: new Blob(['x']), headers: { 'content-disposition': 'attachment; filename="remuneration-jan-2026.pdf"' } }
    throw new Error(url)
  })
  const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { ...rate, id: 'r2' } })
  const del = vi.spyOn(api, 'delete').mockResolvedValue({ data: null })
  return { get, post, del }
}

describe('RemunerationPage', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() }))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })
  afterEach(() => vi.restoreAllMocks())

  it('shows the rates, the total, every person and department, and duties without a rate', async () => {
    mockApi()
    renderRoutes(routes, { path: '/admin/remuneration', auth: admin() })

    expect(await screen.findByText('Total to pay')).toBeInTheDocument()
    expect(screen.getByText('Total to pay').nextSibling).toHaveTextContent(money(1500, 'INR'))
    expect(screen.getByText('Absent (not paid)').nextSibling).toHaveTextContent('1')
    expect(screen.getByText(/1 duty has no rate for its date/)).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /Asha Rao/ })).getByText('+1 without rate')).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /Electronics \(ECE\) 1 2/ })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /Invigilator .* Exam Cell/ })).toHaveTextContent(money(500, 'INR'))
  })

  it('adds and deletes rates', async () => {
    const { post, del } = mockApi()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderRoutes(routes, { path: '/admin/remuneration', auth: admin() })

    await userEvent.selectOptions(await screen.findByLabelText('Role'), 'CHIEF')
    await userEvent.type(screen.getByLabelText('Amount (INR)'), '12.345')
    await userEvent.type(screen.getByLabelText('From'), '2026-02-01')
    await userEvent.click(screen.getByRole('button', { name: 'Add rate' }))
    expect(await screen.findByText(/Enter an amount such as 500/)).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    await userEvent.clear(screen.getByLabelText('Amount (INR)'))
    await userEvent.type(screen.getByLabelText('Amount (INR)'), '750.50')
    await userEvent.click(screen.getByRole('button', { name: 'Add rate' }))
    await waitFor(() => expect(post).toHaveBeenCalledWith('/remuneration/rates', { role: 'CHIEF', amount: '750.50', effectiveFrom: '2026-02-01' }))

    await userEvent.click(screen.getByRole('button', { name: 'Delete Invigilator rate from 2025-01-01' }))
    await waitFor(() => expect(del).toHaveBeenCalledWith('/remuneration/rates/r1', { params: { version: 0 } }))
  })

  it('downloads the remuneration report', async () => {
    const { get } = mockApi()
    renderRoutes(routes, { path: '/admin/remuneration', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: /Excel/ }))

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/exam-periods/p1/reports/remuneration', { params: { format: 'xlsx' }, responseType: 'blob' }),
    )
    expect(await screen.findByText('Downloaded remuneration-jan-2026.pdf.')).toBeInTheDocument()
  })

  it('explains that nothing is published yet', async () => {
    mockApi(pay({ periods: [], periodId: null, periodName: null, chartVersion: null, summary: null, faculty: [], departments: [] }), [])
    renderRoutes(routes, { path: '/admin/remuneration', auth: admin() })

    expect(await screen.findByText(/nothing to pay/)).toBeInTheDocument()
    expect(screen.getByText(/No rates yet/)).toBeInTheDocument()
  })
})

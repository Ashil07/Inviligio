import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders, type AxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'

const admin = () => makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))

const cse = { id: 'd1', code: 'CSE', name: 'Computer Science', active: true, version: 0 }
const old = { id: 'd2', code: 'OLD', name: 'Old Department', active: false, version: 3 }

function httpError(status: number, data: object) {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('fail', 'ERR', config, null, { status, statusText: '', headers: {}, config, data })
}

describe('Departments page', () => {
  beforeEach(() => vi.spyOn(window, 'confirm').mockReturnValue(true))
  afterEach(() => vi.restoreAllMocks())

  it('lists departments and creates a new one', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue({ data: [cse, old] })
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { ...cse, id: 'd3', code: 'ECE' } })
    renderRoutes(routes, { path: '/admin/departments', auth: admin() })

    expect(await screen.findByText('Computer Science')).toBeInTheDocument()
    expect(screen.getByText('Inactive')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Add department' }))
    const panel = screen.getByRole('region', { name: 'New department' })
    await userEvent.type(within(panel).getByLabelText('Code'), 'ece')
    await userEvent.type(within(panel).getByLabelText('Name'), 'Electronics')
    await userEvent.click(within(panel).getByRole('button', { name: 'Create department' }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/departments', { code: 'ece', name: 'Electronics', active: true }))
    await waitFor(() => expect(screen.queryByRole('region', { name: 'New department' })).not.toBeInTheDocument())
    expect(get.mock.calls.length).toBeGreaterThan(1) // list refetched after save
  })

  it('sends the loaded version when editing', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: [old] })
    const put = vi.spyOn(api, 'put').mockResolvedValue({ data: { ...old, active: true, version: 4 } })
    renderRoutes(routes, { path: '/admin/departments', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: 'Edit OLD' }))
    await userEvent.click(screen.getByLabelText('Active'))
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith('/departments/d2', { code: 'OLD', name: 'Old Department', active: true, version: 3 }),
    )
  })

  it('explains why a department in use cannot be deleted', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: [cse] })
    vi.spyOn(api, 'delete').mockRejectedValue(
      httpError(409, { code: 'DEPARTMENT_IN_USE', detail: 'This department has 4 subject(s). Deactivate it instead of deleting.' }),
    )
    renderRoutes(routes, { path: '/admin/departments', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: 'Delete CSE' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Deactivate it instead')
    expect(window.confirm).toHaveBeenCalled()
  })

  it('shows a duplicate code next to the field', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: [] })
    vi.spyOn(api, 'post').mockRejectedValue(httpError(400, { code: 'VALIDATION_FAILED', fieldErrors: { code: 'must be 1-32 letters' } }))
    renderRoutes(routes, { path: '/admin/departments', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: 'Add department' }))
    await userEvent.type(screen.getByLabelText('Code'), 'X')
    await userEvent.type(screen.getByLabelText('Name'), 'Y')
    await userEvent.click(screen.getByRole('button', { name: 'Create department' }))

    expect(await screen.findByText('must be 1-32 letters')).toBeInTheDocument()
  })
})

describe('Subjects page', () => {
  afterEach(() => vi.restoreAllMocks())

  function mockGets() {
    return vi.spyOn(api, 'get').mockImplementation(async (url: string, config?: AxiosRequestConfig) => {
      if (url === '/departments') return { data: [cse, old] }
      if (url === '/subjects') {
        const departmentId = (config?.params as { departmentId?: string } | undefined)?.departmentId
        const items = departmentId === 'd1' || !departmentId
          ? [{ id: 's1', code: 'CS301', name: 'Algorithms', programme: 'B.Tech', semester: 5, active: true, department: cse, version: 0 }]
          : []
        return { data: { items, page: 0, size: 50, total: items.length } }
      }
      throw new Error(url)
    })
  }

  it('filters by department and offers only active departments for new subjects', async () => {
    const get = mockGets()
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} })
    renderRoutes(routes, { path: '/admin/subjects', auth: admin() })

    expect(await screen.findByText('Algorithms')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getAllByLabelText('Department')[0]!, 'd2')
    expect(await screen.findByText('No subjects found.')).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/subjects', expect.objectContaining({ params: expect.objectContaining({ departmentId: 'd2' }) }))

    await userEvent.click(screen.getByRole('button', { name: 'Add subject' }))
    const panel = screen.getByRole('region', { name: 'New subject' })
    const departmentSelect = within(panel).getByLabelText('Department')
    expect(within(departmentSelect).queryByText(/OLD/)).not.toBeInTheDocument()

    await userEvent.click(within(panel).getByRole('button', { name: 'Create subject' }))
    expect(await within(panel).findByText('Choose a department')).toBeInTheDocument()

    await userEvent.type(within(panel).getByLabelText('Code'), 'CS302')
    await userEvent.type(within(panel).getByLabelText('Name'), 'Compilers')
    await userEvent.selectOptions(departmentSelect, 'd1')
    await userEvent.click(within(panel).getByRole('button', { name: 'Create subject' }))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/subjects', {
        code: 'CS302',
        name: 'Compilers',
        departmentId: 'd1',
        programme: '',
        semester: null,
        active: true,
      }),
    )
  })
})

describe('Halls page', () => {
  afterEach(() => vi.restoreAllMocks())

  it('validates capacity before saving', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: [] })
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} })
    renderRoutes(routes, { path: '/admin/halls', auth: admin() })

    await userEvent.click(await screen.findByRole('button', { name: 'Add hall' }))
    await userEvent.type(screen.getByLabelText('Code'), 'H101')
    await userEvent.type(screen.getByLabelText('Name'), 'Main hall')
    const capacity = screen.getByLabelText('Seating capacity')
    await userEvent.clear(capacity)
    await userEvent.type(capacity, '5000')
    await userEvent.click(screen.getByRole('button', { name: 'Create hall' }))

    expect(await screen.findByText('At most 2000')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    await userEvent.clear(capacity)
    await userEvent.type(capacity, '60')
    await userEvent.click(screen.getByLabelText('Laboratory (used for practical exams)'))
    await userEvent.click(screen.getByRole('button', { name: 'Create hall' }))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/halls', {
        code: 'H101',
        name: 'Main hall',
        building: '',
        capacity: 60,
        minInvigilators: 1,
        lab: true,
        active: true,
      }),
    )
  })

  it('totals the seating of active halls', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({
      data: [
        { id: 'h1', code: 'H1', name: 'A', building: null, capacity: 60, minInvigilators: 2, lab: false, active: true, version: 0 },
        { id: 'h2', code: 'H2', name: 'B', building: 'East', capacity: 40, minInvigilators: 1, lab: true, active: true, version: 0 },
        { id: 'h3', code: 'H3', name: 'C', building: null, capacity: 500, minInvigilators: 1, lab: false, active: false, version: 0 },
      ],
    })
    renderRoutes(routes, { path: '/admin/halls', auth: admin() })

    expect(await screen.findByText(/Active seating: 100\./)).toBeInTheDocument()
  })
})

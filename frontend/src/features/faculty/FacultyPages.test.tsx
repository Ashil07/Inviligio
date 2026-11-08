import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders, type AxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import type { AccessDetail, FacultyDetail } from './api'

const admin = () => makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))
const cse = { id: 'd1', code: 'CSE', name: 'Computer Science', active: true, version: 0 }
const algorithms = { id: 's1', code: 'CS301', name: 'Algorithms', programme: null, semester: 5, active: true, department: cse, version: 0 }

function detail(overrides: Partial<FacultyDetail> = {}): FacultyDetail {
  return {
    id: 'f1',
    employeeCode: 'F100',
    fullName: 'Dr. Asha Menon',
    email: 'asha@college.example',
    phone: null,
    department: { id: 'd1', code: 'CSE', name: 'Computer Science' },
    designation: 'Associate Professor',
    employmentType: 'REGULAR',
    joinedOn: '2015-06-01',
    chiefEligible: true,
    dutyWeight: 1,
    active: true,
    subjects: [],
    restrictions: [],
    version: 2,
    ...overrides,
  }
}

const noAccess: AccessDetail = { status: 'NONE', roles: [], loginEmail: null, invitation: null, currentUser: false }

function mockGets(access: AccessDetail = noAccess, faculty = detail()) {
  return vi.spyOn(api, 'get').mockImplementation(async (url: string, config?: AxiosRequestConfig) => {
    if (url === '/departments') return { data: [cse] }
    if (url === '/halls') return { data: [] }
    if (url === '/subjects') {
      const q = ((config?.params as { q?: string } | undefined)?.q ?? '').toLowerCase()
      const items = !q || 'cs301 algorithms'.includes(q) ? [algorithms] : []
      return { data: { items, page: 0, size: 20, total: items.length } }
    }
    if (url === '/faculty/f1') return { data: faculty }
    if (url === '/faculty/f1/access') return { data: access }
    if (url === '/faculty') return { data: { items: [], page: 0, size: 50, total: 0 } }
    throw new Error(`unexpected GET ${url}`)
  })
}

describe('Faculty pages', () => {
  afterEach(() => vi.restoreAllMocks())

  it('creates a faculty member with taught subjects and opens their page', async () => {
    mockGets()
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: detail({ subjects: [algorithms] }) })
    const { router } = renderRoutes(routes, { path: '/admin/faculty/new', auth: admin() })

    await userEvent.type(await screen.findByLabelText('Employee code'), 'f100')
    await userEvent.type(screen.getByLabelText('Full name'), 'Dr. Asha Menon')
    await waitFor(() => expect(screen.getByRole('option', { name: /CSE - Computer Science/ })).toBeInTheDocument())
    await userEvent.selectOptions(screen.getByLabelText('Department'), 'd1')
    await userEvent.type(screen.getByLabelText('Email'), 'asha@college.example')
    await userEvent.type(screen.getByLabelText('Find a subject to add'), 'algo')
    await userEvent.click(await within(screen.getByRole('list', { name: 'Matching subjects' })).findByRole('button', { name: /CS301/ }))
    expect(within(screen.getByRole('list', { name: 'Selected subjects' })).getByText('CS301')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Create faculty member' }))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/faculty', {
        employeeCode: 'f100',
        fullName: 'Dr. Asha Menon',
        departmentId: 'd1',
        email: 'asha@college.example',
        phone: '',
        designation: '',
        employmentType: 'REGULAR',
        joinedOn: null,
        chiefEligible: false,
        dutyWeight: 1,
        active: true,
        subjectIds: ['s1'],
      }),
    )
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/faculty/f1'))
  })

  it('creates an invitation link with extra roles and shows it once', async () => {
    mockGets()
    const post = vi.spyOn(api, 'post').mockResolvedValue({
      data: {
        token: 'tok-123',
        expiresAt: '2026-11-15T10:00:00Z',
        access: { ...noAccess, status: 'INVITED', invitation: { email: 'asha@college.example', roles: ['FACULTY', 'HOD'], createdAt: '2026-11-08T10:00:00Z', expiresAt: '2026-11-15T10:00:00Z' } },
      },
    })
    renderRoutes(routes, { path: '/admin/faculty/f1', auth: admin() })

    await userEvent.click(await screen.findByLabelText('Head of department (sees their department)'))
    await userEvent.click(screen.getByRole('button', { name: 'Create invitation link' }))

    const link = await screen.findByLabelText('Invitation link')
    expect(link).toHaveValue(`${window.location.origin}/accept-invite?token=tok-123`)
    expect(post).toHaveBeenCalledWith('/faculty/f1/access/invitation', { roles: ['HOD'] })
    expect(screen.getByRole('button', { name: 'Revoke invitation' })).toBeInTheDocument()
  })

  it('does not let an admin change their own login', async () => {
    mockGets({ status: 'ACTIVE', roles: ['EXAM_CELL_ADMIN', 'FACULTY'], loginEmail: 'asha@college.example', invitation: null, currentUser: true })
    renderRoutes(routes, { path: '/admin/faculty/f1', auth: admin() })

    expect(await screen.findByText('This is your own login. Another administrator must change it.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save access' })).toBeDisabled()
  })

  it('updates roles of another login', async () => {
    mockGets({ status: 'ACTIVE', roles: ['FACULTY'], loginEmail: 'asha@college.example', invitation: null, currentUser: false })
    const put = vi.spyOn(api, 'put').mockResolvedValue({
      data: { status: 'DISABLED', roles: ['FACULTY'], loginEmail: 'asha@college.example', invitation: null, currentUser: false },
    })
    renderRoutes(routes, { path: '/admin/faculty/f1', auth: admin() })

    await userEvent.click(await screen.findByLabelText('Can sign in'))
    await userEvent.click(screen.getByRole('button', { name: 'Save access' }))

    await waitFor(() => expect(put).toHaveBeenCalledWith('/faculty/f1/access', { roles: [], active: false }))
    expect(await screen.findByText(/Access updated/)).toBeInTheDocument()
  })

  it('adds a restriction and explains server refusals', async () => {
    mockGets()
    const config = { headers: new AxiosHeaders() }
    vi.spyOn(api, 'post').mockRejectedValue(
      new AxiosError('x', 'ERR', config, null, {
        status: 409, statusText: '', headers: {}, config,
        data: { code: 'RESTRICTION_EXISTS', detail: 'This faculty member already has this restriction.' },
      }),
    )
    renderRoutes(routes, { path: '/admin/faculty/f1', auth: admin() })

    await userEvent.selectOptions(await screen.findByLabelText('Add restriction'), 'NOT_CHIEF')
    await userEvent.click(screen.getByRole('button', { name: 'Add restriction' }))

    expect(await screen.findByText('This faculty member already has this restriction.')).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/faculty/f1/restrictions', {
      type: 'NOT_CHIEF', hallId: undefined, departmentId: undefined, maxValue: undefined, reason: undefined,
    })
  })
})

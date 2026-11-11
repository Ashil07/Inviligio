import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'

const activeCollege = makeSession(['EXAM_CELL_ADMIN'], {
  college: { id: 'c1', code: 'GEC', name: 'Government Engineering College', status: 'ACTIVE' },
})

describe('app shell', () => {
  afterEach(() => vi.restoreAllMocks())

  it('groups the exam-cell navigation and marks the current page', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: [] })
    renderRoutes(routes, { path: '/admin/exams', auth: makeAuth(activeCollege) })

    const nav = await screen.findByRole('navigation', { name: 'Examination cell' })
    for (const group of ['Examinations', 'Insights', 'Directory', 'College']) {
      expect(within(nav).getByText(group)).toBeInTheDocument()
    }
    expect(within(nav).getByRole('link', { name: 'Exam periods' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Faculty' })).not.toHaveAttribute('aria-current')
    // College details and policy share one "Settings" entry.
    expect(within(nav).getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/admin/settings/college')
    expect(within(nav).queryByRole('link', { name: 'Policy' })).not.toBeInTheDocument()
  })

  it('shows only setup links while the college is onboarding', async () => {
    renderRoutes(routes, { path: '/admin', auth: makeAuth(makeSession(['EXAM_CELL_ADMIN'])) })

    const nav = await screen.findByRole('navigation', { name: 'Examination cell' })
    expect(within(nav).getAllByRole('link').map((l) => l.textContent)).toEqual(['Overview', 'College setup'])
  })

  it('keeps the settings entry active on the policy tab', async () => {
    vi.spyOn(api, 'get').mockReturnValue(new Promise(() => {}))
    renderRoutes(routes, { path: '/admin/settings/policy', auth: makeAuth(activeCollege) })

    const nav = await screen.findByRole('navigation', { name: 'Examination cell' })
    expect(within(nav).getByRole('link', { name: 'Settings' })).toHaveAttribute('aria-current', 'page')
    const tabs = screen.getByRole('navigation', { name: 'Settings' })
    expect(within(tabs).getByRole('link', { name: 'Examination policy' })).toHaveAttribute('aria-current', 'page')
  })

  it('opens the menu as a drawer on small screens and closes it after navigating', async () => {
    vi.spyOn(api, 'get').mockReturnValue(new Promise(() => {}))
    renderRoutes(routes, { path: '/faculty', auth: makeAuth(makeSession(['FACULTY'], { facultyId: 'f1' })) })

    await userEvent.click(await screen.findByRole('button', { name: 'Open menu' }))
    const drawer = screen.getByRole('dialog', { name: 'Menu' })
    await userEvent.click(within(drawer).getByRole('link', { name: 'My availability' }))

    expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
  })

  it('closes the drawer with Escape', async () => {
    vi.spyOn(api, 'get').mockReturnValue(new Promise(() => {}))
    renderRoutes(routes, { path: '/faculty', auth: makeAuth(makeSession(['FACULTY'], { facultyId: 'f1' })) })

    await userEvent.click(await screen.findByRole('button', { name: 'Open menu' }))
    expect(screen.getByRole('dialog', { name: 'Menu' })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open menu' })).toHaveFocus()
  })
})

describe('landing page', () => {
  it('greets visitors at / with links to register and sign in', async () => {
    renderRoutes(routes, { path: '/' })

    expect(await screen.findByRole('heading', { level: 1, name: /Fair duties/ })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /Register your college/ })[0]).toHaveAttribute('href', '/register')
    expect(screen.getAllByRole('link', { name: 'Sign in' })[0]).toHaveAttribute('href', '/login')
  })

  it('sends signed-in users from / to their area', async () => {
    vi.spyOn(api, 'get').mockReturnValue(new Promise(() => {}))
    const { router } = renderRoutes(routes, { path: '/', auth: makeAuth(makeSession(['FACULTY'], { facultyId: 'f1' })) })

    expect(await screen.findByRole('heading', { name: 'My duties' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/faculty')
  })
})

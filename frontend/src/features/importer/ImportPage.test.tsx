import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'

const admin = () => makeAuth(makeSession(['EXAM_CELL_ADMIN'], { college: { ...makeSession().college, status: 'ACTIVE' } }))

const report = (overrides: object) => ({
  type: 'FACULTY', dryRun: true, applied: false, rows: 2, toCreate: 1, toUpdate: 1,
  created: 0, updated: 0, unchanged: 0, errors: [], ...overrides,
})

describe('ImportPage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('lists problems from the check and offers no import', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({
      data: report({ errors: [{ line: 3, column: 'department_code', message: 'Unknown department code XYZ' }] }),
    })
    renderRoutes(routes, { path: '/admin/import', auth: admin() })

    await userEvent.selectOptions(await screen.findByLabelText('Records'), 'faculty')
    await userEvent.upload(screen.getByLabelText(/CSV file/), new File(['employee_code\n'], 'faculty.csv', { type: 'text/csv' }))
    await userEvent.click(screen.getByRole('button', { name: 'Check file' }))

    expect(await screen.findByText('Unknown department code XYZ')).toBeInTheDocument()
    expect(screen.getByText('department_code')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Import \d+ rows/ })).not.toBeInTheDocument()
  })

  it('checks first, then imports the same file', async () => {
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValueOnce({ data: report({}) })
      .mockResolvedValueOnce({ data: report({ dryRun: false, applied: true, created: 1, updated: 1 }) })
    renderRoutes(routes, { path: '/admin/import', auth: admin() })

    await userEvent.selectOptions(await screen.findByLabelText('Records'), 'faculty')
    const file = new File(['employee_code,full_name,department_code\n'], 'faculty.csv', { type: 'text/csv' })
    await userEvent.upload(screen.getByLabelText(/CSV file/), file)
    await userEvent.click(screen.getByRole('button', { name: 'Check file' }))
    expect(await screen.findByText(/No problems found in 2 rows/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Import 2 rows' }))

    expect(await screen.findByText('Imported 2 rows: 1 created, 1 updated, 0 unchanged.')).toBeInTheDocument()
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2))
    const [url, body, config] = post.mock.calls[1]!
    expect(url).toBe('/imports/faculty')
    expect((body as FormData).get('file')).toBe(file)
    expect(config).toMatchObject({ params: { dryRun: false } })
  })
})

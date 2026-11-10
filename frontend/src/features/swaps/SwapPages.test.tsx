import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { api } from '@/lib/api/client'
import { makeAuth, makeSession, renderRoutes } from '@/test/render'
import type { Colleague, DutyRef, MySwaps, Swap, SwapCheck, SwapOptions } from './api'

const active = { ...makeSession().college, status: 'ACTIVE' as const }
const faculty = () => makeAuth(makeSession(['FACULTY'], { college: active, facultyId: 'f1' }))
const hod = () => makeAuth(makeSession(['HOD', 'FACULTY'], { college: active, facultyId: 'h1' }))

const myDuty = { assignmentId: 'a1', periodId: 'p1', periodName: 'Mar 2027', date: '2027-03-01', sessionLabel: 'FN', startTime: '09:30:00', endTime: '12:30:00', hallCode: 'H101', hallName: 'Main 101', building: null, role: 'INVIGILATOR', status: 'ASSIGNED', colleagues: [] }
const ref = (overrides: Partial<DutyRef> = {}): DutyRef => ({ assignmentId: 'a1', date: '2027-03-01', sessionLabel: 'FN', startTime: '09:30:00', endTime: '12:30:00', hallCode: 'H101', hallName: 'Main 101', role: 'INVIGILATOR', ...overrides })
const meera = { id: 'f2', code: 'E2', name: 'Meera Nair', departmentCode: 'ECE' }
const ravi = { id: 'f1', code: 'E1', name: 'Ravi Kumar', departmentCode: 'ECE' }

function swap(overrides: Partial<Swap> = {}): Swap {
  return {
    id: 's1', periodId: 'p1', periodName: 'Mar 2027', status: 'PENDING_RECIPIENT', requester: ravi, requesterDuty: ref(), target: meera, targetDuty: null,
    reason: 'Clinic', createdAt: '2027-02-20T10:00:00Z', recipientRespondedAt: null, recipientNote: null, reviewedByName: null, reviewedAt: null,
    reviewNote: null, invalidReason: null, appliedAt: null, version: 3, ...overrides,
  }
}

const meeraOption: Colleague = {
  faculty: meera,
  duties: 1,
  canTakeHandover: true,
  warnings: [],
  exchangeable: [ref({ assignmentId: 'b1', date: '2027-03-02', sessionLabel: 'AN', startTime: '13:30:00', endTime: '16:30:00', hallCode: 'LAB1' })],
}
const options: SwapOptions = { duty: ref(), swapsEnabled: true, colleagues: [meeraOption] }

function mockApi({ mine = { sent: [], received: [] } as MySwaps, check = { allowed: true, blocking: [], warnings: [] } as SwapCheck, enabled = true, list = [] as Swap[], after = swap() } = {}) {
  const get = vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url === '/me/duties') return { data: { timeZone: 'Asia/Kolkata', today: '2027-02-27', chiefDuties: 0, upcoming: [myDuty], past: [] } }
    if (url === '/policy') return { data: { version: 1, saved: true, settings: { swaps: { swapsEnabled: enabled, recipientMustAccept: true, hodApprovalRequired: false, adminCanReassignDirectly: true } } } }
    if (url === '/me/swap-requests') return { data: mine }
    if (url === '/me/swap-options') return { data: options }
    if (url === '/swap-requests') return { data: list }
    throw new Error(url)
  })
  const post = vi.spyOn(api, 'post').mockImplementation(async (url: string) => {
    if (url === '/me/swap-requests/validate') return { data: check }
    return { data: after }
  })
  return { get, post }
}

describe('Swap pages', () => {
  afterEach(() => vi.restoreAllMocks())

  it('asks a colleague to take a duty after the rules allow it', async () => {
    const { post } = mockApi()
    renderRoutes(routes, { path: '/faculty/swaps?duty=a1', auth: faculty() })

    // The duty from the link is preselected; only colleagues the rules allow are offered.
    await waitFor(() => expect(screen.getByLabelText('Your duty')).toHaveValue('a1'))
    await userEvent.selectOptions(await screen.findByLabelText('Colleague'), 'f2')
    expect(await screen.findByText('This swap fits the rules.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/me/swap-requests/validate', { assignmentId: 'a1', targetFacultyId: 'f2', targetAssignmentId: null })

    // Trading instead of handing over is checked again.
    await userEvent.selectOptions(screen.getByLabelText('In return'), 'b1')
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/me/swap-requests/validate', { assignmentId: 'a1', targetFacultyId: 'f2', targetAssignmentId: 'b1' }),
    )
    await userEvent.type(screen.getByLabelText('Reason (optional)'), 'Clinic')
    await userEvent.click(screen.getByRole('button', { name: 'Ask for this swap' }))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/me/swap-requests', { assignmentId: 'a1', targetFacultyId: 'f2', targetAssignmentId: 'b1', reason: 'Clinic' }),
    )
    expect(await screen.findByText(/Sent to Meera Nair/)).toBeInTheDocument()
    // The form is cleared for the next request.
    await waitFor(() => expect(screen.getByLabelText('Your duty')).toHaveValue(''))
    expect(screen.getByLabelText('Colleague')).toHaveValue('')
  })

  it('asks for one of their duties when a colleague can only exchange', async () => {
    const { post } = mockApi()
    meeraOption.canTakeHandover = false
    try {
      renderRoutes(routes, { path: '/faculty/swaps?duty=a1', auth: faculty() })

      await waitFor(() => expect(screen.getByLabelText('Your duty')).toHaveValue('a1'))
      await userEvent.selectOptions(await screen.findByLabelText('Colleague'), 'f2')
      expect(screen.getByRole('option', { name: /Meera Nair .* exchange only/ })).toBeInTheDocument()
      expect(screen.getByText(/choose one of their duties to take in exchange/)).toBeInTheDocument()
      expect(post).not.toHaveBeenCalled()
      expect(screen.getByRole('button', { name: 'Ask for this swap' })).toBeDisabled()

      await userEvent.selectOptions(screen.getByLabelText('In return'), 'b1')
      await waitFor(() =>
        expect(post).toHaveBeenCalledWith('/me/swap-requests/validate', { assignmentId: 'a1', targetFacultyId: 'f2', targetAssignmentId: 'b1' }),
      )
      expect(await screen.findByText('This swap fits the rules.')).toBeInTheDocument()
    } finally {
      meeraOption.canTakeHandover = true
    }
  })

  it('explains a swap that breaks a rule and does not let it be sent', async () => {
    mockApi({ check: { allowed: false, blocking: [{ code: 'UNAVAILABLE', message: 'Meera Nair is on approved leave or unavailable.' }], warnings: [] } })
    renderRoutes(routes, { path: '/faculty/swaps?duty=a1', auth: faculty() })

    await waitFor(() => expect(screen.getByLabelText('Your duty')).toHaveValue('a1'))
    await userEvent.selectOptions(await screen.findByLabelText('Colleague'), 'f2')

    expect(await screen.findByText('Meera Nair is on approved leave or unavailable.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ask for this swap' })).toBeDisabled()
  })

  it('accepts a request from a colleague', async () => {
    const waiting = swap({ requester: meera, target: ravi })
    const { post } = mockApi({ mine: { sent: [], received: [waiting] }, after: swap({ requester: meera, target: ravi, status: 'APPROVED', appliedAt: '2027-02-21T09:00:00Z' }) })
    renderRoutes(routes, { path: '/faculty/swaps', auth: faculty() })

    expect(await screen.findByText('Meera Nair asks you to swap')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/swap-requests/s1/accept', { version: 3, note: undefined }))
    expect(await screen.findByText(/Done: Ravi Kumar takes/)).toBeInTheDocument()
  })

  it('says so when the college does not allow swaps', async () => {
    mockApi({ enabled: false })
    renderRoutes(routes, { path: '/faculty/swaps', auth: faculty() })

    expect(await screen.findByText(/does not allow duty swaps/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Your duty')).not.toBeInTheDocument()
  })

  it('lets the head of department approve a swap', async () => {
    const pending = swap({ status: 'PENDING_HOD', targetDuty: ref({ assignmentId: 'b1', hallCode: 'LAB1', sessionLabel: 'AN' }) })
    const { post } = mockApi({ list: [pending], after: { ...pending, status: 'APPROVED' } })
    renderRoutes(routes, { path: '/hod/swaps', auth: hod() })

    expect(await screen.findByText(/Ravi Kumar takes .* LAB1; Meera Nair takes .* H101/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/swap-requests/s1/approve', { version: 3, note: undefined }))
    expect(await screen.findByText(/Approved and done/)).toBeInTheDocument()
  })
})

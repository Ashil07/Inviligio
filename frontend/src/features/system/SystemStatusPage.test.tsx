import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/lib/api/client'
import { SystemStatusPage } from './SystemStatusPage'

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SystemStatusPage />
    </QueryClientProvider>,
  )
}

describe('SystemStatusPage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('shows API and database as up with the schema version', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({
      data: { application: 'invigilo', serverTime: '2026-11-08T10:00:00Z', database: { status: 'UP', schemaVersion: '1' } },
    })

    renderPage()

    expect(await screen.findByText('Schema version 1')).toBeInTheDocument()
    expect(screen.getAllByLabelText('Up')).toHaveLength(2)
  })

  it('reports an unreachable server', async () => {
    vi.spyOn(api, 'get').mockRejectedValue(
      new AxiosError('Network Error', 'ERR_NETWORK', { headers: new AxiosHeaders() }),
    )

    renderPage()

    expect(await screen.findByText('Cannot reach the Invigilo server.')).toBeInTheDocument()
    expect(screen.getAllByLabelText('Down')).toHaveLength(2)
  })
})

/**
 * @vitest-environment jsdom
 */
import '@testing-library/jest-dom/vitest'
import { render, screen, act, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'

const apiFetchMock = vi.hoisted(() => vi.fn()) as ReturnType<typeof vi.fn>

vi.mock('../../lib/api', () => ({
  apiFetch: apiFetchMock,
}))

vi.mock('../../lib/wiremockClient', () => ({
  fetchWiremockComponent: vi.fn(),
}))

import SutDetailPanel from './SutDetailPanel'

describe('SutDetailPanel', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    apiFetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => [
        {
          id: 'sut-1',
          name: 'SUT One',
          type: 'demo',
          endpoints: {
            default: { kind: 'http', baseUrl: 'http://example' },
          },
        },
      ],
    })
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('polls sut-environments every 5 seconds', async () => {
    render(
      <MemoryRouter>
        <SutDetailPanel sutId="sut-1" />
      </MemoryRouter>,
    )

    await act(async () => {
      await Promise.resolve()
    })
    expect(apiFetchMock).toHaveBeenCalledTimes(1)

    await act(async () => {
      await apiFetchMock.mock.results[0]!.value
      await Promise.resolve()
    })
    expect(screen.getByText('SUT One')).toBeInTheDocument()

    await act(async () => {
      vi.advanceTimersByTime(5000)
      await Promise.resolve()
    })

    expect(apiFetchMock).toHaveBeenCalledTimes(2)
  })
})

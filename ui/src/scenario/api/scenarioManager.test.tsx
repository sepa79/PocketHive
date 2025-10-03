import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, act } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { useUpsertScenario } from './scenarioManager'

vi.mock('../../lib/api', () => ({
  apiFetch: vi.fn(),
}))

const { apiFetch } = await import('../../lib/api')

describe('useUpsertScenario', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  test('preserves cache updates when custom onSuccess is provided', async () => {
    const scenario = {
      id: 'abc',
      name: 'Example',
      description: 'desc',
      sutAssets: [],
      datasetAssets: [],
      swarmTemplates: [],
    }

    const mockedFetch = apiFetch as unknown as ReturnType<typeof vi.fn>

    mockedFetch.mockResolvedValue(
      new Response(JSON.stringify(scenario), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const extraSuccess = vi.fn()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpsertScenario({ onSuccess: extraSuccess }), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ id: 'abc', name: 'Example', description: 'desc', sutAssets: [], datasetAssets: [], swarmTemplates: [] })
    })

    expect(queryClient.getQueryData(['scenarios', 'abc'])).toEqual(scenario)
    expect(invalidateSpy).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['scenarios'] }))
    expect(extraSuccess).toHaveBeenCalled()
    expect(extraSuccess.mock.calls[0][0]).toStrictEqual(scenario)
  })
})

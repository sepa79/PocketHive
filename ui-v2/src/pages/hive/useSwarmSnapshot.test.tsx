import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useSwarmSnapshot } from './useSwarmSnapshot'
import type { AuthenticatedUser } from '../../lib/auth'
import { initialStateFixture } from '../../testFixtures/swarmState'

const caller: AuthenticatedUser = {
  id: 'synthetic-user', username: 'synthetic-user', displayName: 'Synthetic',
  active: true, authProvider: 'DEV', grants: [],
}

function response(id: string, runId = 'synthetic-run') {
  return new Response(JSON.stringify({
    ...initialStateFixture(), id, runId,
  }), { headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers() })

it('loads through ingress and clears successful details on a failed refresh', async () => {
  const fetch = vi.fn().mockResolvedValue(response('one'))
  vi.stubGlobal('fetch', fetch)
  const { result } = renderHook(() => useSwarmSnapshot('one', caller, 'token'))
  await waitFor(() => expect(result.current.snapshot?.runId).toBe('synthetic-run'))
  expect(fetch.mock.calls[0][0]).toBe('/orchestrator/api/swarms/one')
  fetch.mockResolvedValue(new Response('', { status: 404 }))
  await act(() => result.current.reload())
  expect(result.current.snapshot).toBeNull()
  expect(result.current.error).toBe("Swarm 'one' was not found.")
})

it('does not replace selected swarm data with an old delayed response', async () => {
  const pending: Array<(response: Response) => void> = []
  const signals: AbortSignal[] = []
  vi.stubGlobal('fetch', vi.fn((_url, options) => {
    signals.push(options.signal)
    return new Promise<Response>(resolve => pending.push(resolve))
  }))
  const { result, rerender } = renderHook(({ id }) => useSwarmSnapshot(id, caller, 'token'), { initialProps: { id: 'one' } })
  rerender({ id: 'two' })
  expect(signals[0].aborted).toBe(true)
  await act(async () => pending[1](response('two', 'run-two')))
  await waitFor(() => expect(result.current.snapshot?.id).toBe('two'))
  await act(async () => pending[0](response('one', 'run-one')))
  expect(result.current.snapshot?.runId).toBe('run-two')
})

it('ignores a retained reload from an obsolete selection without blocking current polling', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  const fetch = vi.fn((url: string) => Promise.resolve(response(url.endsWith('/one') ? 'one' : 'two')))
  vi.stubGlobal('fetch', fetch)
  const { result, rerender } = renderHook(({ id }) => useSwarmSnapshot(id, caller, 'token'), { initialProps: { id: 'one' } })
  await act(() => result.current.reload())
  expect(result.current.loading).toBe(false)
  expect(result.current.error).toBeNull()
  expect(result.current.snapshot?.id).toBe('one')
  const obsoleteReload = result.current.reload
  rerender({ id: 'two' })
  await act(() => result.current.reload())
  expect(result.current.snapshot?.id).toBe('two')
  let finish: ((value: Response) => void) | undefined
  fetch.mockImplementation(() => new Promise<Response>(resolve => { finish = resolve }))
  await act(async () => { void obsoleteReload() })
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(result.current.snapshot?.id).toBe('two')
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  expect(fetch).toHaveBeenCalledTimes(3)
  expect(fetch.mock.calls[2][0]).toBe('/orchestrator/api/swarms/two')
  await act(async () => finish?.(response('two', 'refreshed-current-run')))
  expect(result.current.snapshot?.runId).toBe('refreshed-current-run')
})

it.each(['caller', 'token'] as const)('hides data immediately when the %s changes', async changed => {
  let finish: ((value: Response) => void) | undefined
  const fetch = vi.fn().mockResolvedValueOnce(response('one'))
    .mockImplementation(() => new Promise<Response>(resolve => { finish = resolve }))
  vi.stubGlobal('fetch', fetch)
  const { result, rerender } = renderHook(props => useSwarmSnapshot('one', props.user, props.token), {
    initialProps: { user: caller, token: 'first' },
  })
  await waitFor(() => expect(result.current.snapshot).not.toBeNull())
  rerender({ user: changed === 'caller' ? { ...caller, id: 'another-user' } : caller,
    token: changed === 'token' ? 'second' : 'first' })
  expect(result.current.snapshot).toBeNull()
  expect(result.current.loading).toBe(true)
  await act(async () => finish?.(response('one', 'new-caller-run')))
  await waitFor(() => expect(result.current.snapshot?.runId).toBe('new-caller-run'))
})

it('aborts on unmount and does not read without a selection', async () => {
  const fetch = vi.fn().mockImplementation(() => new Promise<Response>(() => {}))
  vi.stubGlobal('fetch', fetch)
  const { unmount } = renderHook(() => useSwarmSnapshot('one', caller, 'token'))
  const signal: AbortSignal = fetch.mock.calls[0][1].signal
  unmount()
  expect(signal.aborted).toBe(true)
  renderHook(() => useSwarmSnapshot(null, caller, 'token'))
  expect(fetch).toHaveBeenCalledTimes(1)
})

it('joins a slow initial request across polling ticks instead of starving it', async () => {
  vi.useFakeTimers()
  let finish: ((value: Response) => void) | undefined
  const fetch = vi.fn(() => new Promise<Response>(resolve => { finish = resolve }))
  vi.stubGlobal('fetch', fetch)
  const { result } = renderHook(() => useSwarmSnapshot('one', caller, 'token'))
  await act(async () => { await vi.advanceTimersByTimeAsync(15_000) })
  expect(fetch).toHaveBeenCalledTimes(1)
  await act(async () => finish?.(response('one')))
  expect(result.current.snapshot?.id).toBe('one')
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  expect(fetch).toHaveBeenCalledTimes(2)
})

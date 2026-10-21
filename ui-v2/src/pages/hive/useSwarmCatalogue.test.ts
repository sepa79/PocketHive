import { afterEach, beforeEach, expect, it, vi } from 'vitest'

// Exercise the hook's asynchronous request behaviour without a browser renderer.
// React state writes are observed; effects expose their real cleanup callback.
const harness = vi.hoisted(() => ({ writes: [] as unknown[], initialStates: [] as unknown[], cleanup: () => {} }))
vi.mock('react', () => ({
  useState: (initial: unknown) => [
    harness.initialStates.length ? harness.initialStates.shift() : initial,
    (value: unknown) => harness.writes.push(value),
  ],
  useRef: (current: unknown) => ({ current }),
  useCallback: (callback: unknown) => callback,
  useEffect: (effect: () => () => void) => { harness.cleanup = effect() },
}))
import { useSwarmCatalogue } from './useSwarmCatalogue'

beforeEach(() => { harness.writes = []; harness.initialStates = [] })
afterEach(() => vi.unstubAllGlobals())

function pendingRequests() {
  const finish: Array<(id: string) => void> = []
  const fetch = vi.fn((url: string) => {
    if (url.endsWith('/access/swarms')) {
      return Promise.resolve(new Response('{"swarms":[{"swarmId":"visible","canRun":true,"canManage":false}]}'))
    }
    return new Promise<Response>(resolve => finish.push(id => resolve(new Response(JSON.stringify([{ id }])))))
  })
  vi.stubGlobal('fetch', fetch)
  return { finish, fetch }
}
function displayedIds() {
  return harness.writes.filter(Array.isArray).flatMap(entries => entries.map(entry => entry.id))
}

function accessWrites() {
  return harness.writes.filter((value): value is Map<string, { canRun: boolean; canManage: boolean }> => value instanceof Map)
}

it.each([false, true])('keeps confirmed permissions while a refresh is pending (showLoading=%s)', async (showLoading) => {
  const { finish } = pendingRequests()
  const { reload } = useSwarmCatalogue(null)
  const first = reload()
  finish[0]('first')
  await first
  expect(accessWrites()).toHaveLength(1)
  expect(accessWrites()[0].get('visible')?.canRun).toBe(true)

  const refresh = reload({ showLoading })
  expect(accessWrites()).toHaveLength(1)
  finish[1]('refreshed')
  await refresh
  expect(accessWrites()).toHaveLength(2)
  expect(accessWrites().every((value) => value.has('visible'))).toBe(true)
})

it('clears confirmed permissions when a refresh fails', async () => {
  const { finish, fetch } = pendingRequests()
  const { reload } = useSwarmCatalogue(null)
  const first = reload()
  finish[0]('first')
  await first

  fetch.mockRejectedValue(new Error('access unavailable'))
  const refresh = reload({ showLoading: false })
  expect(accessWrites()).toHaveLength(1)
  await refresh
  expect(accessWrites().at(-1)?.size).toBe(0)
  expect(harness.writes).toContain('access unavailable')
})

it('replaces confirmed permissions with a fresh revocation', async () => {
  const { finish, fetch } = pendingRequests()
  const { reload } = useSwarmCatalogue(null)
  const first = reload()
  finish[0]('first')
  await first

  fetch.mockImplementation((url: string) => Promise.resolve(new Response(JSON.stringify(
    url.endsWith('/access/swarms')
      ? { swarms: [{ swarmId: 'visible', canRun: false, canManage: false }] }
      : [{ id: 'visible' }],
  ))))
  await reload({ showLoading: false })
  expect(accessWrites().at(-1)?.get('visible')).toMatchObject({ canRun: false, canManage: false })
})

it('hides a confirmed observation belonging to a different caller', () => {
  vi.stubGlobal('fetch', vi.fn())
  const previousCaller = { id: 'previous', username: 'previous', displayName: 'Previous', active: true, authProvider: 'DEV', grants: [] }
  const nextCaller = { ...previousCaller, id: 'next', username: 'next' }
  harness.initialStates = [[], new Map([['visible', { swarmId: 'visible', canRun: true, canManage: true }]]), false, null, previousCaller]
  expect(useSwarmCatalogue(nextCaller).access.size).toBe(0)
})

it('background ticks join a slow request and allow its result to become visible', async () => {
  const { finish, fetch } = pendingRequests()
  const { reload } = useSwarmCatalogue(null)
  const first = reload()
  const tick1 = reload({ showLoading: false })
  const tick2 = reload({ showLoading: false })
  expect(fetch).toHaveBeenCalledTimes(2)
  finish[0]('first')
  await Promise.all([first, tick1, tick2])
  expect(displayedIds()).toEqual(['first'])
  const next = reload({ showLoading: false })
  expect(fetch).toHaveBeenCalledTimes(4)
  finish[1]('second')
  await next
  expect(displayedIds()).toEqual(['first', 'second'])
})

it('explicit refresh supersedes a request without old completion clearing the new in-flight load', async () => {
  const { finish, fetch } = pendingRequests()
  const { reload } = useSwarmCatalogue(null)
  const first = reload()
  const refresh = reload()
  finish[0]('stale')
  await first
  const tick = reload({ showLoading: false })
  expect(fetch).toHaveBeenCalledTimes(4)
  finish[1]('current')
  await Promise.all([refresh, tick])
  expect(displayedIds()).toEqual(['current'])
})

it('caller/unmount cleanup prevents an outstanding result from publishing state', async () => {
  const { finish } = pendingRequests()
  const { reload } = useSwarmCatalogue(null)
  const request = reload()
  harness.cleanup()
  finish[0]('old-caller')
  await request
  expect(displayedIds()).toEqual([])
  expect(harness.writes.some(value => value instanceof Map && value.size > 0)).toBe(false)
})

it('a failed request releases the background polling slot for recovery', async () => {
  const fetch = vi.fn().mockRejectedValue(new Error('offline'))
  vi.stubGlobal('fetch', fetch)
  const { reload } = useSwarmCatalogue(null)
  await reload()
  expect(harness.writes).toContain('offline')
  await reload({ showLoading: false })
  expect(fetch).toHaveBeenCalledTimes(4)
})

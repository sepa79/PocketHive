import { afterEach, beforeEach, expect, it, vi } from 'vitest'

// Exercise the hook's asynchronous request behaviour without a browser renderer.
// React state writes are observed; effects expose their real cleanup callback.
const harness = vi.hoisted(() => ({ writes: [] as unknown[], cleanup: () => {} }))
vi.mock('react', () => ({
  useState: (initial: unknown) => [initial, (value: unknown) => harness.writes.push(value)],
  useRef: (current: unknown) => ({ current }),
  useCallback: (callback: unknown) => callback,
  useEffect: (effect: () => () => void) => { harness.cleanup = effect() },
}))
import { useSwarmCatalogue } from './useSwarmCatalogue'

beforeEach(() => { harness.writes = [] })
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

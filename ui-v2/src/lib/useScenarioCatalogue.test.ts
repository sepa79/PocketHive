import { afterEach, beforeEach, expect, it, vi } from 'vitest'

// Async orchestration tests with stubbed hook primitives; not React rendering/E2E tests.
const harness = vi.hoisted(() => ({ writes: [] as unknown[], cleanup: () => {} }))
vi.mock('react', () => ({
  useState: (initial: unknown) => [initial, (value: unknown) => harness.writes.push(value)],
  useRef: (current: unknown) => ({ current }),
  useCallback: (callback: unknown) => callback,
  useEffect: (effect: () => () => void) => { harness.cleanup = effect() },
}))
import { useScenarioCatalogue } from './useScenarioCatalogue'
beforeEach(() => { harness.writes = [] })
afterEach(() => vi.unstubAllGlobals())
function observations() {
  return harness.writes.filter(value => value !== null && typeof value === 'object' && 'entries' in value) as Array<{entries: {bundleKey: string}[], access: ReadonlyMap<string, boolean>}>
}
function requests() {
  const finish: Array<(key: string) => void> = []
  const fetch = vi.fn((url: string) => {
    if (url.endsWith('/access/bundles')) return Promise.resolve(new Response('{"bundles":[{"bundleKey":"team/a","canManage":true}]}'))
    return new Promise<Response>(resolve => finish.push(key => resolve(new Response(JSON.stringify([
      { bundleKey: key, bundlePath: key, id: key, name: key, bees: [] },
    ])))))
  })
  vi.stubGlobal('fetch', fetch)
  return { fetch, finish }
}
it('consumes the read catalogue and explicit edit decisions without local grants', async () => {
  const {fetch,finish} = requests()
  const {reload} = useScenarioCatalogue('read', null)
  const refresh = reload()
  finish[1]('team/a')
  await refresh
  expect(observations()[0].access.get('team/a')).toBe(true)
  expect(observations()[0].entries[0].bundleKey).toBe('team/a')
  expect(fetch.mock.calls.map(call => call[0])).toContain('/scenario-manager/scenarios/bundles/workspaces')
  harness.cleanup(); finish[0]('stale')
})
it('uses the run-filtered template catalogue without requesting edit permissions', async () => {
  const {fetch,finish} = requests()
  const {reload} = useScenarioCatalogue('run', null)
  const refresh = reload(); finish[1]('runnable'); await refresh
  expect(observations()[0].entries[0].bundleKey).toBe('runnable')
  expect(observations()[0].access.size).toBe(0)
  expect(fetch.mock.calls.map(call => call[0])).toEqual(['/scenario-manager/api/templates', '/scenario-manager/api/templates'])
  harness.cleanup(); finish[0]('stale')
})
it('ignores superseded responses and responses after caller/unmount cleanup', async () => {
  const {finish} = requests()
  const {reload} = useScenarioCatalogue('run', null)
  const older = reload(); const current = reload()
  finish[2]('current'); await current
  finish[1]('older'); await older
  expect(observations().map(value => value.entries[0].bundleKey)).toEqual(['current'])
  const closing = reload(); harness.cleanup(); finish[3]('closed'); await closing
  finish[0]('initial')
  expect(observations()).toHaveLength(1)
})
it('does not fetch for a closed modal', async () => {
  const {fetch} = requests()
  const result = useScenarioCatalogue('run', null, false)
  await result.reload()
  expect(fetch).not.toHaveBeenCalled()
  expect(result.entries).toEqual([])
})
it('exposes access failures rather than publishing an editable catalogue', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
  const {reload} = useScenarioCatalogue('read', null)
  await reload()
  expect(harness.writes).toContain('offline')
  expect(observations()).toEqual([])
})

import { beforeEach, expect, it, vi } from 'vitest'
// Controlled hook primitives exercise state publication, caller gating and cleanup.
// These are not browser rendering tests; effects/cleanup are explicitly scheduled.
const harness = vi.hoisted(() => ({ stored: null as unknown, generation: {current: 0}, effect: () => (() => {}) }))
vi.mock('react', () => ({
  useState: () => [harness.stored, (value: unknown) => { harness.stored = value }],
  useRef: () => harness.generation,
  useCallback: (callback: unknown) => callback,
  useEffect: (effect: () => () => void) => { harness.effect = effect },
}))
import { useAccessObservation } from './useAccessObservation'
beforeEach(() => { harness.stored = null; harness.generation = {current: 0} })
const caller = {}
function pending() {
  let resolve!: (value: boolean) => void
  const promise = new Promise<boolean>(finish => { resolve = finish })
  return {resolve, promise}
}
it('does not expose old decisions for a changed caller or token before effects run', async () => {
  const load = vi.fn().mockResolvedValue(true)
  await useAccessObservation(caller, 'a', load).reload()
  expect(useAccessObservation(caller, 'a', load).value).toBe(true)
  expect(useAccessObservation({}, 'a', load).status).toBe('loading')
  expect(useAccessObservation(caller, 'b', load).value).toBeNull()
})
it('invalidates decisions immediately on retry and exposes failures separately from denial', async () => {
  const load = vi.fn().mockResolvedValueOnce(true).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(false)
  await useAccessObservation(caller, 'a', load).reload()
  const retry = useAccessObservation(caller, 'a', load).reload()
  expect(useAccessObservation(caller, 'a', load).status).toBe('loading')
  expect(useAccessObservation(caller, 'a', load).value).toBeNull()
  await retry
  expect(useAccessObservation(caller, 'a', load)).toMatchObject({status: 'error', value: null, error: 'offline'})
  await useAccessObservation(caller, 'a', load).reload()
  expect(useAccessObservation(caller, 'a', load)).toMatchObject({status: 'ready', value: false, error: null})
})
it('ignores late completion after a newer request', async () => {
  const first = pending(); const second = pending()
  const load = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
  const api = useAccessObservation(caller, 'a', load)
  const older = api.reload(); const latest = api.reload()
  second.resolve(false); await latest
  first.resolve(true); await older
  expect(useAccessObservation(caller, 'a', load).value).toBe(false)
})
it('effect cleanup prevents pending work from publishing state after unmount', async () => {
  const request = pending(); const load = vi.fn().mockReturnValue(request.promise)
  useAccessObservation(caller, 'a', load)
  const cleanup = harness.effect()
  cleanup(); request.resolve(true)
  await request.promise; await Promise.resolve()
  expect(useAccessObservation(caller, 'a', load).value).toBeNull()
})
it('disabled and anonymous consumers neither fetch nor expose cached decisions', async () => {
  const load = vi.fn().mockResolvedValue(true)
  await useAccessObservation(caller, 'a', load).reload()
  load.mockClear()
  const disabled = useAccessObservation(caller, 'a', load, false)
  await disabled.reload()
  expect(disabled).toMatchObject({status: 'idle', value: null})
  await useAccessObservation(null, null, load).reload()
  expect(load).not.toHaveBeenCalled()
})

import { beforeEach, expect, it, vi } from 'vitest'
// Controlled hooks test async loading transitions, not browser rendering.
const h = vi.hoisted(() => ({ refs: [] as {current: unknown}[], cursor: 0, effect: () => (() => {}), fetch: vi.fn() }))
vi.mock('react', () => ({
  useRef: (current: unknown) => { const slot = h.cursor++; return h.refs[slot] ?? (h.refs[slot] = {current}) },
  useState: (value: unknown) => [value, () => {}],
  useCallback: (callback: unknown) => callback,
  useEffect: (effect: () => () => void) => { h.effect = effect },
}))
vi.mock('./auth', () => ({ listAdminUsers: h.fetch }))
import { useAdminUsersLoader } from './useAdminUsersLoader'
beforeEach(() => { h.refs = []; h.fetch.mockReset() })
function render(status: 'ready' | 'loading' | 'error', onLoaded: ReturnType<typeof vi.fn>, caller = 'admin', token = 'token', allowed = status === 'ready') {
  h.cursor = 0
  return useAdminUsersLoader(caller, token, status, allowed, onLoaded)
}
const tick = async () => { await Promise.resolve(); await Promise.resolve() }
it('same-admin access refresh does not reload or overwrite selection and draft', async () => {
  h.fetch.mockResolvedValue([{id: 'first'}, {id: 'admin'}])
  let form = {selected: 'first', draft: 'initial'}
  const apply = vi.fn(() => { form = {selected: 'first', draft: 'server'} })
  render('ready', apply); let cleanup = h.effect(); await tick()
  form = {selected: 'admin', draft: 'edited own profile'}
  cleanup(); render('loading', apply); cleanup = h.effect()
  cleanup(); render('ready', apply); h.effect(); await tick()
  expect(h.fetch).toHaveBeenCalledTimes(1)
  expect(form).toEqual({selected: 'admin', draft: 'edited own profile'})
  expect(apply).toHaveBeenCalledTimes(1)
})
it('failed permission observation and recovery also retain the form', async () => {
  h.fetch.mockResolvedValue([]); const apply = vi.fn()
  render('ready', apply); let cleanup = h.effect(); await tick()
  cleanup(); render('error', apply); cleanup = h.effect()
  cleanup(); render('ready', apply); h.effect(); await tick()
  expect(apply).toHaveBeenCalledTimes(1)
})
it('explicit reload passes the selected account while same-caller auto refresh does not run', async () => {
  h.fetch.mockResolvedValue([]); const apply = vi.fn()
  render('ready', apply); h.effect(); await tick()
  await render('ready', apply).reload('selected-admin')
  expect(apply).toHaveBeenLastCalledWith([], 'selected-admin')
  expect(h.fetch).toHaveBeenCalledTimes(2)
})
it('new caller/session loads afresh and hides the old initialized state', async () => {
  h.fetch.mockResolvedValue([]); const apply = vi.fn()
  render('ready', apply); const cleanup = h.effect(); await tick()
  cleanup(); const next = render('ready', apply, 'another-admin', 'new-token')
  expect(next.initialized).toBe(false)
  h.effect(); await tick()
  expect(h.fetch).toHaveBeenCalledTimes(2)
})
it('confirmed denial invalidates the loaded list and blocks explicit reload', async () => {
  h.fetch.mockResolvedValue([]); const apply = vi.fn()
  render('ready', apply); let cleanup = h.effect(); await tick()
  cleanup(); const denied = render('ready', apply, 'admin', 'token', false); cleanup = h.effect()
  await denied.reload(); expect(h.fetch).toHaveBeenCalledTimes(1)
  cleanup(); render('ready', apply); h.effect(); await tick()
  expect(h.fetch).toHaveBeenCalledTimes(2)
})
it('does not publish a pending response cancelled by permission refresh', async () => {
  let finish!: (users: unknown[]) => void
  h.fetch.mockReturnValue(new Promise(resolve => { finish = resolve }))
  const apply = vi.fn()
  render('ready', apply); const cleanup = h.effect()
  cleanup(); render('loading', apply); h.effect()
  finish([{id: 'old'}]); await tick()
  expect(apply).not.toHaveBeenCalled()
})

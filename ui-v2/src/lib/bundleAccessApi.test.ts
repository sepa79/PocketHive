import { afterEach, expect, it, vi } from 'vitest'
import { loadBundleAccess } from './bundleAccessApi'
afterEach(() => vi.unstubAllGlobals())
it('loads the explicit backend decisions through ingress without caching', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ bundles: [
    { bundleKey: 'team/a', canManage: true }, { bundleKey: 'team/b', canManage: false },
  ] })))
  vi.stubGlobal('fetch', fetch)
  expect([...await loadBundleAccess()]).toEqual([['team/a', true], ['team/b', false]])
  expect(fetch).toHaveBeenCalledWith('/scenario-manager/api/access/bundles', expect.objectContaining({ cache: 'no-store' }))
})
it.each([{}, { bundles: null }, { bundles: [{ bundleKey: 'a', canManage: 'true' }] },
  { bundles: [{ bundleKey: '', canManage: true }] },
  { bundles: [{ bundleKey: 'a', canManage: true }, { bundleKey: 'a', canManage: false }] },
])('rejects invalid access responses %j', async payload => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(payload))))
  await expect(loadBundleAccess()).rejects.toThrow('Invalid bundle permissions')
})
it('accepts an explicitly empty visible set', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"bundles":[]}')))
  expect((await loadBundleAccess()).size).toBe(0)
})
it('does not turn HTTP failure into an empty success', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 403 })))
  await expect(loadBundleAccess()).rejects.toThrow('403')
})

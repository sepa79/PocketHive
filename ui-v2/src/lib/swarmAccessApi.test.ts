import { afterEach, expect, it, vi } from 'vitest'
import { loadSwarmAccess } from './swarmAccessApi'
afterEach(() => vi.unstubAllGlobals())

it('consumes backend permission decisions without inferring one from another', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ swarms: [
    { swarmId: 'a', canRun: true, canManage: false },
    { swarmId: 'b', canRun: false, canManage: true },
  ] })))
  vi.stubGlobal('fetch', fetch)
  const result = await loadSwarmAccess()
  expect(result.get('a')).toEqual({ swarmId: 'a', canRun: true, canManage: false })
  expect(result.get('b')?.canRun).toBe(false)
  expect(result.get('absent')).toBeUndefined()
  expect(fetch).toHaveBeenCalledWith('/orchestrator/api/access/swarms', {
    headers: { Accept: 'application/json' }, cache: 'no-store',
  })
})
it('accepts the empty visible set', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"swarms":[]}')))
  expect((await loadSwarmAccess()).size).toBe(0)
})
it.each([403, 503])('propagates HTTP %i rather than fabricating permissions', async status => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status })))
  await expect(loadSwarmAccess()).rejects.toThrow(`HTTP ${status}`)
})
it.each([
  {}, { swarms: null }, { swarms: [{ swarmId: 'a', canRun: true }] },
  { swarms: [{ swarmId: 'a', canRun: 'true', canManage: false }] },
  { swarms: [{ swarmId: 'a', canRun: false, canManage: false }, { swarmId: 'a', canRun: true, canManage: true }] },
])('rejects malformed projections without partial permissions: %j', async body => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body))))
  await expect(loadSwarmAccess()).rejects.toThrow('Invalid swarm permissions')
})

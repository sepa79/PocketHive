import { afterEach, expect, it, vi } from 'vitest'
import { scenarioOperationsAccessApi } from './scenarioOperationsAccessApi'
afterEach(() => vi.unstubAllGlobals())
it('loads explicit decisions with the requested caller token and no cache', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ canReload: true, canUpload: true })))
  vi.stubGlobal('fetch', fetch)
  expect(await scenarioOperationsAccessApi('caller-token')).toEqual({ canReload: true, canUpload: true })
  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/^\/(auth-service|scenario-manager)\//), {
    cache: 'no-store', headers: {Accept: 'application/json', Authorization: 'Bearer caller-token'},
  })
})
it.each([{}, { canReload: 'true' }, null])('rejects malformed decisions %j', async value => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(value))))
  await expect(scenarioOperationsAccessApi('t')).rejects.toThrow('Invalid permissions response')
})
it('preserves denied booleans', async () => {
  const value = { canReload: false, canUpload: false }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(value))))
  expect(await scenarioOperationsAccessApi('t')).toEqual(value)
})
it('does not treat HTTP failures as permission denials', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', {status: 503})))
  await expect(scenarioOperationsAccessApi('t')).rejects.toThrow('503')
})

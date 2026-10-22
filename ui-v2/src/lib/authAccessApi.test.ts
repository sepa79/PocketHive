import { afterEach, expect, it, vi } from 'vitest'
import { authAccessApi } from './authAccessApi'
import deploymentAccess from '../../../auth-service/src/test/resources/auth-access-deployment-manager.json'
import folderAccess from '../../../auth-service/src/test/resources/auth-access-folder-manager.json'
afterEach(() => vi.unstubAllGlobals())
it('loads explicit decisions with the requested caller token and no cache', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ canAccessPocketHive: true, canRunPocketHive: true, canManageDeployment: true, canManageUsers: true })))
  vi.stubGlobal('fetch', fetch)
  expect(await authAccessApi('caller-token')).toEqual({ canAccessPocketHive: true, canRunPocketHive: true, canManageDeployment: true, canManageUsers: true })
  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/^\/(auth-service|scenario-manager)\//), {
    cache: 'no-store', headers: {Accept: 'application/json', Authorization: 'Bearer caller-token'},
  })
})
it.each([{}, { canAccessPocketHive: 'true' }, null])('rejects malformed decisions %j', async value => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(value))))
  await expect(authAccessApi('t')).rejects.toThrow('Invalid permissions response')
})
it('preserves denied booleans', async () => {
  const value = { canAccessPocketHive: false, canRunPocketHive: false, canManageDeployment: false, canManageUsers: false }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(value))))
  expect(await authAccessApi('t')).toEqual(value)
})
it('does not treat HTTP failures as permission denials', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', {status: 503})))
  await expect(authAccessApi('t')).rejects.toThrow('503')
})

it.each([deploymentAccess, folderAccess])('decodes the producer-owned deployment decision %j', async value => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(value)))
  expect(await authAccessApi('caller-token')).toEqual(value)
})
it.each([undefined, null, 'true'])('rejects missing or malformed deployment decisions %j', async decision => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...deploymentAccess, canManageDeployment: decision })))
  await expect(authAccessApi('caller-token')).rejects.toThrow('Invalid permissions response')
})

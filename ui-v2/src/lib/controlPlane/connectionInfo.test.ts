import { afterEach, expect, it, vi } from 'vitest'
import { loadControlPlaneConnectionInfo } from './connectionInfo'

afterEach(() => vi.unstubAllGlobals())
it('uses the ingress API projection verbatim', async () => {
  const projection = { subscriptionDestination: '/exchange/tenant.control/#', destinationPrefix: '/exchange/tenant.control/' }
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(projection)))
  vi.stubGlobal('fetch', fetch)
  await expect(loadControlPlaneConnectionInfo()).resolves.toEqual(projection)
  expect(fetch).toHaveBeenCalledWith('/orchestrator/api/control-plane/info')
})
it('fails on HTTP error or incomplete projection instead of inventing a destination', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response('', { status: 503 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ subscriptionDestination: '/exchange/tenant/#' })))
  vi.stubGlobal('fetch', fetch)
  await expect(loadControlPlaneConnectionInfo()).rejects.toThrow('HTTP 503')
  await expect(loadControlPlaneConnectionInfo()).rejects.toThrow('Invalid control-plane connection information')
})

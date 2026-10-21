import { vi } from 'vitest'

export const anonymous = { user: null, canEdit: false, configured: true }
export const editor = { user: { username: 'operator', displayName: 'Operator' }, canEdit: true, configured: true }
export const grant = {
  device_code: 'synthetic-device-secret', user_code: 'ABCD-EFGH',
  verification_uri: 'http://localhost:8089/auth-service/oauth/device_verification',
  verification_uri_complete: 'http://localhost:8089/auth-service/oauth/device_verification?user_code=ABCD-EFGH',
  expires_in: 300,
}
export const issued = { access_token: 'phweb_synthetic', token_type: 'Bearer', expires_in: 600, scope: 'pockethive:web' }

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

export function popup() {
  const tab = { opener: {}, closed: false, location: { replace: vi.fn() }, close: vi.fn() }
  tab.close.mockImplementation(() => { tab.closed = true })
  return tab
}

export function mockFetch(handler: (request: Request) => Response | Promise<Response>) {
  const fetcher = vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
    Promise.resolve(handler(input instanceof Request ? input : new Request(input, init))))
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

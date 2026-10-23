import { afterEach, expect, test, vi } from 'vitest'
import { PocketHiveSessionApi } from './PocketHiveSessionApi'

const api = new PocketHiveSessionApi()
const signal = new AbortController().signal
const user = { id: 'fixture', username: 'local-viewer', displayName: 'Viewer', active: true, authProvider: 'DEV', grants: [] }

function fixture(responses: { body: unknown; status?: number }[]) {
  vi.stubGlobal('window', { location: { origin: 'http://localhost:49153' } })
  const requests: Request[] = []
  vi.stubGlobal('fetch', vi.fn(async (request: Request) => {
    requests.push(request)
    const next = responses.shift()
    if (!next) throw new Error('Unexpected test request')
    return new Response(next.body === undefined ? null : JSON.stringify(next.body), { status: next.status ?? 200 })
  }))
  return requests
}

afterEach(() => vi.unstubAllGlobals())

test('starts the PH device client on the current preview origin without any callback', async () => {
  const verification = 'http://localhost:8089/auth-service/oauth/device_verification'
  const requests = fixture([{ body: { device_code: 'synthetic-device', user_code: 'ABCD', verification_uri: verification,
    verification_uri_complete: verification + '?user_code=ABCD', expires_in: 300 } }])
  await api.start(null, signal)
  expect(requests[0].url).toBe('http://localhost:49153/auth-service/oauth/device_authorization')
  expect(requests[0].redirect).toBe('error')
  expect(requests[0].headers.has('Authorization')).toBe(false)
  const form = new URLSearchParams(await requests[0].text())
  expect(Object.fromEntries(form)).toEqual({ client_id: 'pockethive-device', resource: 'urn:pockethive:web', scope: 'pockethive:web' })
})

test('uses a fresh full PH identity without deriving editing rights from its grants', async () => {
  const requests = fixture([{ body: user }])
  expect(await api.session(null, signal)).toEqual({ user: null, canEdit: false, configured: true })
  expect(requests).toHaveLength(0)
  expect(await api.session('phweb_synthetic', signal)).toEqual({ user, canEdit: false, configured: true })
  expect(requests[0].headers.get('Authorization')).toBe('Bearer phweb_synthetic')
})

test.each([{ ...user, active: false }, { ...user, grants: [{}] }, { username: 'local-admin' }])('rejects inactive or malformed identity', async supplied => {
  fixture([{ body: supplied }])
  await expect(api.session('phweb_synthetic', signal)).rejects.toThrow('active identity')
})

test('maps pending separately and rejects an unexpected successful scope', async () => {
  fixture([{ status: 400, body: { error: 'authorization_pending' } },
    { body: { access_token: 'phweb_synthetic', token_type: 'Bearer', expires_in: 300, scope: 'wrong' } }])
  expect(await api.poll('synthetic-device', null, signal)).toEqual({ kind: 'authorization_pending' })
  await expect(api.poll('synthetic-device', null, signal)).rejects.toThrow('scope')
})

test('discovers central logout and revokes the same PH client token before returning its URL', async () => {
  const url = 'http://localhost:8089/auth-service/oauth/device/logout?client_id=pockethive-device'
  const requests = fixture([{ body: { logoutUri: url } }, { body: undefined }])
  expect(await api.logout('phweb_synthetic', signal)).toBe(url)
  expect(requests[1].url).toBe('http://localhost:49153/auth-service/oauth/revoke')
  expect(requests[1].headers.has('Authorization')).toBe(false)
  expect(Object.fromEntries(new URLSearchParams(await requests[1].text()))).toEqual({
    client_id: 'pockethive-device', token: 'phweb_synthetic', token_type_hint: 'access_token',
  })
})

test('does not return a logout destination after failed revocation', async () => {
  fixture([{ body: { logoutUri: 'http://localhost:8089/auth-service/oauth/device/logout?client_id=pockethive-device' } },
    { status: 503, body: { message: 'unsafe backend detail' } }])
  await expect(api.logout('phweb_synthetic', signal)).rejects.toThrow('unavailable')
})

test('maps a revoked identity to the shared unauthorized outcome', async () => {
  fixture([{ status: 401, body: {} }])
  await expect(api.session('phweb_revoked', signal)).rejects.toMatchObject({ code: 'unauthorized' })
})

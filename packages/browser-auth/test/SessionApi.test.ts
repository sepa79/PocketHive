import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionApi } from '../src/SessionApi.js'
import { anonymous, editor, grant, issued, json, mockFetch } from './fixtures.js'

describe('SessionApi', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-19T12:00:00Z')) })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it('uses only the agreed same-origin endpoints and validates fresh identity', async () => {
    const requests: Request[] = []
    mockFetch(async request => {
      requests.push(request)
      switch (new URL(request.url).pathname) {
        case '/api/session': return json(editor)
        case '/api/session/device/start': expect(await request.json()).toEqual({}); return json(grant)
        case '/api/session/device/poll': expect(await request.json()).toEqual({ deviceCode: grant.device_code }); return json(issued)
        case '/api/session/logout': expect(await request.json()).toEqual({}); return json({ logoutUrl: 'http://localhost:8089/auth-service/oauth/device/logout' })
        default: throw new Error('Unexpected endpoint')
      }
    })
    const api = new SessionApi()
    const signal = new AbortController().signal
    expect(await api.session('phweb_input', signal)).toEqual(editor)
    expect(await api.start('phweb_input', signal)).toMatchObject({ deviceCode: grant.device_code, intervalMs: 5000 })
    expect(await api.poll(grant.device_code, 'phweb_input', signal)).toMatchObject({ kind: 'authorized', token: { accessToken: issued.access_token } })
    expect(await api.logout('phweb_input', signal)).toBe('http://localhost:8089/auth-service/oauth/device/logout')
    for (const request of requests) {
      expect(new URL(request.url).origin).toBe(window.location.origin)
      expect(request.headers.get('Authorization')).toBe('Bearer phweb_input')
      expect(request.redirect).toBe('error')
      expect(request.cache).toBe('no-store')
      expect(request.url).not.toContain('phweb_')
      expect(request.url).not.toContain(grant.device_code)
    }
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each(['authorization_pending', 'slow_down', 'access_denied', 'expired_token'])('maps only the documented 400 outcome %s', async error => {
    mockFetch(() => json({ error }, 400))
    expect(await new SessionApi().poll('secret', null, new AbortController().signal)).toEqual({ kind: error })
  })

  it('does not turn an unknown or wrong-status error into a retry', async () => {
    const fetcher = mockFetch(() => json({ error: 'untrusted secret phweb_hidden' }, 400))
    await expect(new SessionApi().poll('secret', null, new AbortController().signal)).rejects.toMatchObject({ code: 'request_failed' })
    fetcher.mockImplementationOnce(async () => json({ error: 'authorization_pending' }))
    await expect(new SessionApi().poll('secret', null, new AbortController().signal)).rejects.toMatchObject({ code: 'protocol' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([
    { ...grant, interval: 0 }, { ...grant, interval: null }, { ...grant, expires_in: -1 },
    { ...grant, expires_in: Number.MAX_SAFE_INTEGER },
    { ...grant, verification_uri_complete: 'https://evil.example/approve?user_code=ABC' },
    { ...grant, verification_uri_complete: 'javascript:alert(1)' },
    { ...grant, verification_uri_complete: 'http://user:password@localhost:8089/approve' },
    { ...grant, verification_uri_complete: 'http://localhost:8089/approve?device_code=secret' },
  ])('rejects invalid device grants', async value => {
    mockFetch(() => json(value))
    await expect(new SessionApi().start(null, new AbortController().signal)).rejects.toMatchObject({ code: 'protocol' })
  })

  it('never infers editing from an identity name and requires explicit session fields', async () => {
    const fetcher = mockFetch(() => json({ ...editor, canEdit: false }))
    expect((await new SessionApi().session('token', new AbortController().signal)).canEdit).toBe(false)
    fetcher.mockImplementationOnce(async () => json({ ...anonymous, canEdit: true }))
    await expect(new SessionApi().session(null, new AbortController().signal)).rejects.toMatchObject({ code: 'protocol' })
    fetcher.mockImplementationOnce(async () => json({ user: null }))
    await expect(new SessionApi().session(null, new AbortController().signal)).rejects.toMatchObject({ code: 'protocol' })
  })

  it('distinguishes 401, failures and explicit local-only logout', async () => {
    const fetcher = mockFetch(() => json({ error: 'phweb_secret' }, 401))
    await expect(new SessionApi().session('token', new AbortController().signal)).rejects.toMatchObject({ code: 'unauthorized' })
    fetcher.mockImplementationOnce(async () => json({ error: 'phweb_secret' }, 502))
    await expect(new SessionApi().logout('token', new AbortController().signal)).rejects.toMatchObject({ code: 'request_failed', message: 'Pocket auth is unavailable. Please try again.' })
    fetcher.mockImplementationOnce(async () => json({ logoutUrl: '' }))
    expect(await new SessionApi().logout('token', new AbortController().signal)).toBe('')
  })
})

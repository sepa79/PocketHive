import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { parseDevicePoll, SessionHttpClient } from '../src/index.js'
import { MAX_JSON_BYTES } from '../src/sessionProtocol.js'
import { json, mockFetch } from './fixtures.js'

describe('SessionHttpClient', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-19T12:00:00Z')) })
  afterEach(() => {
    expect(vi.getTimerCount()).toBe(0)
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('returns bounded JSON and the request start time without retaining transport state', async () => {
    const requestedAt = Date.now()
    mockFetch(() => {
      vi.setSystemTime(requestedAt + 1000)
      return json({ configured: true })
    })
    expect(await new SessionHttpClient().request('/auth-service/api/auth/device/config', { method: 'GET' }, null,
      new AbortController().signal)).toEqual({ body: { configured: true }, status: 200, requestedAt })
  })

  it('preserves URL-encoded OAuth bodies while owning bearer, cache, credentials and redirect policy', async () => {
    const fetcher = mockFetch(() => json({ error: 'authorization_pending' }, 400))
    const body = new URLSearchParams({ client_id: 'synthetic-device-client', device_code: 'synthetic-secret' })
    const result = await new SessionHttpClient().request('/auth-service/oauth/token', {
      method: 'POST', body, headers: { Authorization: 'Bearer caller-override', 'Content-Type': 'application/x-www-form-urlencoded' },
      redirect: 'follow', cache: 'force-cache', credentials: 'include',
    }, 'phweb_owned', new AbortController().signal, { polling: true })
    expect(parseDevicePoll(result.body, result.status, result.requestedAt)).toEqual({ kind: 'authorization_pending' })
    const request = fetcher.mock.calls[0][0] as Request
    expect(request.url).toBe(`${window.location.origin}/auth-service/oauth/token`)
    expect(request.method).toBe('POST')
    expect(await request.text()).toBe(body.toString())
    expect(request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded')
    expect(request.headers.get('Authorization')).toBe('Bearer phweb_owned')
    expect(request.redirect).toBe('error')
    expect(request.credentials).toBe('same-origin')
    expect(request.cache).toBe('no-store')
  })

  it('removes caller authorization when the explicit credential is null', async () => {
    const fetcher = mockFetch(() => json({ configured: true }))
    await new SessionHttpClient().request('/auth-service/api/auth/device/config', {
      headers: { Authorization: 'Bearer override' },
    }, null, new AbortController().signal)
    expect((fetcher.mock.calls[0][0] as Request).headers.has('Authorization')).toBe(false)
  })

  it.each([200, 204])('accepts explicit empty revocation success %s without reading or draining the body', async status => {
    const response = new Response(status === 204 ? null : new ReadableStream<Uint8Array>(), { status })
    const read = response.body ? vi.spyOn(response.body, 'getReader') : vi.fn()
    const cancel = response.body ? vi.spyOn(response.body, 'cancel') : vi.fn()
    mockFetch(() => response)
    const requestedAt = Date.now()
    expect(await new SessionHttpClient().request('/auth-service/oauth/revoke', {
      method: 'POST', body: new URLSearchParams({ token: 'phweb_revoked' }),
    }, null, new AbortController().signal, { empty: true })).toEqual({ body: null, status, requestedAt })
    expect(read).not.toHaveBeenCalled()
    expect(cancel).not.toHaveBeenCalled()
    expect(response.bodyUsed).toBe(false)
  })

  it.each([400, 401, 403, 500, 302])('does not accept HTTP %s as empty revocation success or expose its body', async status => {
    const response = new Response('phweb_server-secret', { status })
    const read = vi.spyOn(response.body!, 'getReader')
    mockFetch(() => response)
    await expect(new SessionHttpClient().request('/auth-service/oauth/revoke', { method: 'POST' },
      'phweb_owned', new AbortController().signal, { empty: true })).rejects.toMatchObject({
      code: status === 401 ? 'unauthorized' : 'request_failed',
      message: status === 401 ? 'Your Pocket session is no longer valid. Sign in again.' : 'Pocket auth is unavailable. Please try again.',
    })
    expect(read).not.toHaveBeenCalled()
  })

  it('permits HTTP 400 JSON only when polling is explicit', async () => {
    mockFetch(() => json({ error: 'authorization_pending' }, 400))
    const http = new SessionHttpClient()
    const signal = new AbortController().signal
    await expect(http.request('/auth-service/oauth/token', { method: 'POST' }, null, signal)).rejects.toMatchObject({ code: 'request_failed' })
    expect(await http.request('/auth-service/oauth/token', { method: 'POST' }, null, signal, { polling: true }))
      .toMatchObject({ body: { error: 'authorization_pending' }, status: 400 })
  })

  it.each(['https://evil.example/collect', '//evil.example/collect', 'http://user:password@localhost:49152/api'])('rejects %s before sending credentials', async path => {
    const fetcher = mockFetch(() => json({}))
    await expect(new SessionHttpClient().request(path, {}, 'phweb_owned', new AbortController().signal))
      .rejects.toMatchObject({ code: 'cross_origin' })
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('times out a hanging request and cleans up its timer', async () => {
    mockFetch(request => new Promise((_resolve, reject) => {
      request.signal.addEventListener('abort', () => reject(request.signal.reason), { once: true })
    }))
    const result = new SessionHttpClient().request('/auth-service/api/auth/me', {}, null, new AbortController().signal)
    const check = expect(result).rejects.toMatchObject({ code: 'request_timeout' })
    await vi.advanceTimersByTimeAsync(15000)
    await check
  })

  it('keeps the timeout active until the JSON body has finished', async () => {
    mockFetch(request => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        request.signal.addEventListener('abort', () => controller.error(request.signal.reason), { once: true })
      },
    })))
    const result = new SessionHttpClient().request('/auth-service/api/auth/me', {}, null, new AbortController().signal)
    const check = expect(result).rejects.toMatchObject({ code: 'request_timeout' })
    await vi.advanceTimersByTimeAsync(15000)
    await check
  })

  it('aborts on cancellation without leaking server details', async () => {
    const abort = new AbortController()
    mockFetch(request => new Promise((_resolve, reject) => {
      request.signal.addEventListener('abort', () => reject(new Error('phweb_server-secret')), { once: true })
    }))
    const result = new SessionHttpClient().request('/auth-service/api/auth/me', {}, null, abort.signal)
    const check = expect(result).rejects.toMatchObject({ name: 'AbortError' })
    abort.abort()
    await check
  })

  it('sanitizes network failures without retrying', async () => {
    const fetcher = mockFetch(() => { throw new Error('phweb_server-secret') })
    await expect(new SessionHttpClient().request('/auth-service/api/auth/me', {}, null, new AbortController().signal))
      .rejects.toMatchObject({ code: 'request_failed', message: 'Could not reach Pocket auth. Please try again.' })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('rejects oversized, invalid JSON and invalid UTF-8 without forwarding payloads', async () => {
    const responses = [
      new Response('x'.repeat(MAX_JSON_BYTES + 1)),
      new Response('phweb_secret not JSON'),
      new Response(new Uint8Array([0xff])),
    ]
    for (const response of responses) {
      mockFetch(() => response)
      await expect(new SessionHttpClient().request('/auth-service/api/auth/me', {}, null, new AbortController().signal))
        .rejects.toMatchObject({ code: 'protocol', message: 'Invalid or oversized Pocket auth response.' })
    }
  })
})

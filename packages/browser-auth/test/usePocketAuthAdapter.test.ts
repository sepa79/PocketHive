import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'
import { BrowserTokenStore, parseDeviceAuthorization, PocketAuthError, usePocketAuth } from '../src/index.js'
import type { PocketSession, SessionApiPort, StoredToken } from '../src/index.js'
import { grant, json, mockFetch, popup } from './fixtures.js'

interface FullSession extends PocketSession {
  user: {
    id: string
    username: string
    displayName: string
    active: boolean
    grants: { role: string; scope: string }[]
  } | null
  canEdit: false
  configured: true
}

const storageKey = 'POCKET_AUTH_ADAPTER_SYNTHETIC_TEST'
const anonymousSession: FullSession & { user: null } = { user: null, canEdit: false, configured: true }
const fullSession: FullSession = {
  user: {
    id: 'synthetic-user-id', username: 'operator', displayName: 'Operator', active: true,
    grants: [{ role: 'ADMIN', scope: 'synthetic-scope' }],
  },
  canEdit: false, configured: true,
}

function adapter() {
  return {
    anonymousSession,
    session: vi.fn<SessionApiPort<FullSession>['session']>().mockImplementation(async token => token === null ? anonymousSession : fullSession),
    start: vi.fn<SessionApiPort<FullSession>['start']>().mockImplementation(async () => parseDeviceAuthorization(grant, Date.now())),
    poll: vi.fn<SessionApiPort<FullSession>['poll']>().mockImplementation(async () => ({
      kind: 'authorized', token: { accessToken: 'phweb_new', expiresAt: Date.now() + 600000 },
    })),
    logout: vi.fn<SessionApiPort<FullSession>['logout']>().mockResolvedValue(''),
  } satisfies SessionApiPort<FullSession>
}

function stored(expiresIn = 600000): StoredToken {
  const token = { accessToken: 'phweb_existing', expiresAt: Date.now() + expiresIn }
  new BrowserTokenStore(storageKey).write(token)
  return token
}

async function settle() {
  await act(async () => {
    for (let turn = 0; turn < 40; turn++) await Promise.resolve()
  })
}

async function advance(milliseconds: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(milliseconds) })
  await settle()
}

describe('usePocketAuth explicit session adapter', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    vi.setSystemTime(new Date('2026-10-19T12:00:00Z'))
    sessionStorage.clear()
    vi.spyOn(window, 'open').mockImplementation(() => popup() as unknown as Window)
  })
  afterEach(async () => {
    try {
      cleanup()
      await advance(1)
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
      vi.unstubAllGlobals()
      vi.restoreAllMocks()
    }
  })

  it('uses the typed anonymous projection, restores the full identity and keeps a stable controller on rerender', async () => {
    const token = stored()
    const api = adapter()
    const fetcher = mockFetch(() => { throw new Error('No default transport permitted') })
    const { result, rerender } = renderHook(({ onError }) => usePocketAuth({ storageKey, api, onError }), {
      initialProps: { onError: vi.fn() },
    })
    expectTypeOf(result.current.session).toEqualTypeOf<FullSession>()
    expect(result.current.session).toBe(anonymousSession)
    expect(result.current.getAccessToken()).toBeNull()
    await settle()
    expect(result.current.session).toBe(fullSession)
    expect(result.current.session.user?.id).toBe('synthetic-user-id')
    expect(result.current.session.user?.grants).toEqual([{ role: 'ADMIN', scope: 'synthetic-scope' }])
    expect(result.current.session.canEdit).toBe(false)
    expect(result.current.getAccessToken()).toBe(token.accessToken)
    const { signIn, refresh, getAccessToken } = result.current
    const onError = vi.fn()
    rerender({ onError })
    await settle()
    expect(result.current.signIn).toBe(signIn)
    expect(result.current.refresh).toBe(refresh)
    expect(result.current.getAccessToken).toBe(getAccessToken)
    expect(api.session).toHaveBeenCalledTimes(1)
    expect(fetcher).not.toHaveBeenCalled()
    api.session.mockRejectedValueOnce(new PocketAuthError('request_failed', 'Pocket auth is unavailable. Please try again.'))
    await act(async () => { await result.current.refresh() })
    expect(onError).toHaveBeenCalledTimes(1)
    expect(result.current.session).toBe(fullSession)
    expect(new BrowserTokenStore(storageKey).read()).toEqual(token)
  })

  it('installs full identity after device confirmation without persisting identity or inferring editing', async () => {
    const api = adapter()
    const { result } = renderHook(() => usePocketAuth<FullSession>({ storageKey, api }))
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    await advance(5000)
    expect(result.current.session).toBe(fullSession)
    expect(result.current.signingIn).toBe(false)
    expect(result.current.getAccessToken()).toBe('phweb_new')
    expect(api.session).toHaveBeenLastCalledWith('phweb_new', expect.any(AbortSignal))
    expect(JSON.parse(sessionStorage.getItem(storageKey)!)).toEqual({ accessToken: 'phweb_new', expiresAt: Date.now() + 600000 })
    expect(sessionStorage.length).toBe(1)
  })

  it('refreshes full identity through the same session path without extending credential lifetime', async () => {
    const token = stored(10000)
    const api = adapter()
    const refreshed: FullSession = { ...fullSession, user: { ...fullSession.user!, displayName: 'Updated', grants: [] } }
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    await advance(5000)
    let finish!: (session: FullSession) => void
    api.session.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    act(() => { void result.current.refresh() })
    await settle()
    expect(result.current.loading).toBe(true)
    expect(result.current.session).toBe(fullSession)
    expect(api.session).toHaveBeenLastCalledWith(token.accessToken, expect.any(AbortSignal))
    finish(refreshed)
    await settle()
    expect(result.current.loading).toBe(false)
    expect(result.current.session).toBe(refreshed)
    expect(new BrowserTokenStore(storageKey).read()).toEqual(token)
    await advance(5000)
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.error?.code).toBe('session_expired')
    expect(result.current.getAccessToken()).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
  })

  it('clears the full identity and credential when session refresh returns 401', async () => {
    stored()
    const api = adapter()
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    api.session.mockRejectedValueOnce(new PocketAuthError('unauthorized', 'Your Pocket session is no longer valid. Sign in again.'))
    await act(async () => { await result.current.refresh() })
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.error?.code).toBe('unauthorized')
    expect(result.current.loading).toBe(false)
    expect(result.current.getAccessToken()).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    expect(api.start).not.toHaveBeenCalled()
  })

  it.each(['access_denied', 'expired_token'] as const)('preserves an existing full identity when replacement sign-in ends in %s', async kind => {
    const token = stored()
    const api = adapter()
    api.poll.mockResolvedValue({ kind })
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    await advance(5000)
    expect(result.current.error?.code).toBe(kind)
    expect(result.current.signingIn).toBe(false)
    expect(result.current.session).toBe(fullSession)
    expect(result.current.getAccessToken()).toBe(token.accessToken)
    expect(new BrowserTokenStore(storageKey).read()).toEqual(token)
    await advance(100000)
    expect(api.poll).toHaveBeenCalledTimes(1)
    expect(api.session).toHaveBeenCalledTimes(1)
  })

  it('clears the full identity on a matching product 401 without another identity writer', async () => {
    stored()
    const api = adapter()
    mockFetch(() => json({}, 401))
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    await act(async () => { expect((await result.current.fetchWithAuth('/api/protected')).status).toBe(401) })
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.getAccessToken()).toBeNull()
    expect(result.current.error?.code).toBe('unauthorized')
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    expect(api.session).toHaveBeenCalledTimes(1)
  })

  it.each(['success', 'failure'] as const)('ignores late refresh %s after a matching product 401 invalidates its credential', async outcome => {
    stored()
    const api = adapter()
    const onError = vi.fn()
    mockFetch(() => json({}, 401))
    const { result } = renderHook(() => usePocketAuth({ storageKey, api, onError }))
    await settle()
    let finish!: (session: FullSession) => void
    let fail!: (error: PocketAuthError) => void
    api.session.mockImplementationOnce(() => new Promise((resolve, reject) => { finish = resolve; fail = reject }))
    let refresh!: Promise<void>
    act(() => { refresh = result.current.refresh() })
    await settle()
    await act(async () => { await result.current.fetchWithAuth('/api/protected') })
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.getAccessToken()).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    expect(result.current.error?.code).toBe('unauthorized')
    await act(async () => {
      if (outcome === 'success') finish(fullSession)
      else fail(new PocketAuthError('request_failed', 'Synthetic delayed failure'))
      await refresh
    })
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.getAccessToken()).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    expect(result.current.error?.code).toBe('unauthorized')
    expect(result.current.loading).toBe(false)
    expect(api.session).toHaveBeenCalledTimes(2)
    expect(api.start).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledTimes(1)
    await advance(600000)
    expect(result.current.getAccessToken()).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    expect(result.current.error?.code).toBe('unauthorized')
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('keeps an explicit replacement sign-in alive when its old credential gets a product 401', async () => {
    stored()
    const api = adapter()
    let finishRequest!: (response: Response) => void
    mockFetch(() => new Promise(resolve => { finishRequest = resolve }))
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    const oldRequest = result.current.fetchWithAuth('/api/protected')
    const replacement: FullSession = { ...fullSession, user: { ...fullSession.user!, id: 'explicit-replacement' } }
    api.session.mockResolvedValueOnce(replacement)
    act(() => { void result.current.signIn() })
    await settle()
    await act(async () => { finishRequest(json({}, 401)); await oldRequest })
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.signingIn).toBe(true)
    await advance(5000)
    expect(result.current.session).toBe(replacement)
    expect(result.current.getAccessToken()).toBe('phweb_new')
    expect(new BrowserTokenStore(storageKey).read()?.accessToken).toBe('phweb_new')
    expect(result.current.error).toBeNull()
  })

  it('ignores a late product 401 for the old bearer after installing a new full identity', async () => {
    stored()
    const api = adapter()
    let finish!: (response: Response) => void
    mockFetch(() => new Promise(resolve => { finish = resolve }))
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    const oldRequest = result.current.fetchWithAuth('/api/protected')
    const replacement: FullSession = { ...fullSession, user: { ...fullSession.user!, id: 'replacement-user-id', grants: [] } }
    api.session.mockResolvedValueOnce(replacement)
    act(() => { void result.current.signIn() })
    await settle()
    await advance(5000)
    finish(json({}, 401))
    await act(async () => { await oldRequest })
    expect(result.current.session).toBe(replacement)
    expect(result.current.getAccessToken()).toBe('phweb_new')
    expect(result.current.error).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()?.accessToken).toBe('phweb_new')
  })

  it('cancels an in-flight custom poll and ignores a late success', async () => {
    const api = adapter()
    let finish!: (value: Awaited<ReturnType<typeof api.poll>>) => void
    api.poll.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    await advance(5000)
    const signal = api.poll.mock.calls[0][2]
    act(() => result.current.cancelSignIn())
    expect(signal.aborted).toBe(true)
    finish({ kind: 'authorized', token: { accessToken: 'phweb_late', expiresAt: Date.now() + 600000 } })
    await settle()
    expect(result.current.session).toBe(anonymousSession)
    expect(result.current.getAccessToken()).toBeNull()
    expect(result.current.error).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    expect(api.session).toHaveBeenCalledTimes(1)
  })

  it('aborts a replaced adapter and never lets its late grant start polling', async () => {
    const first = adapter()
    const second = adapter()
    let finish!: (value: Awaited<ReturnType<typeof first.start>>) => void
    first.start.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const { result, rerender } = renderHook(({ api }) => usePocketAuth({ storageKey, api }), { initialProps: { api: first } })
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    const signal = first.start.mock.calls[0][1]
    rerender({ api: second })
    await settle()
    expect(signal.aborted).toBe(true)
    finish(parseDeviceAuthorization(grant, Date.now()))
    await advance(5000)
    expect(first.poll).not.toHaveBeenCalled()
    expect(second.start).not.toHaveBeenCalled()
    expect(second.session).toHaveBeenCalledTimes(1)
    expect(result.current.signingIn).toBe(false)
    expect(result.current.session).toBe(anonymousSession)
  })

  it('does not persist an identity or token when a custom session check finishes after unmount', async () => {
    stored()
    const api = adapter()
    let finish!: (session: FullSession) => void
    api.session.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const { unmount } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    const write = vi.spyOn(Storage.prototype, 'setItem')
    const signal = api.session.mock.calls[0][1]
    unmount()
    expect(signal.aborted).toBe(true)
    finish(fullSession)
    await settle()
    expect(write).not.toHaveBeenCalled()
  })

  it('projects an expired bearer as null during render without publishing or writing before the expiry timer runs', async () => {
    const token = stored(1000)
    const api = adapter()
    const onError = vi.fn()
    const { result, rerender } = renderHook(() => {
      const auth = usePocketAuth({ storageKey, api, onError })
      return { auth, accessToken: auth.getAccessToken() }
    })
    await settle()
    expect(result.current.accessToken).toBe(token.accessToken)
    const remove = vi.spyOn(Storage.prototype, 'removeItem')
    const errors = vi.spyOn(console, 'error')
    vi.setSystemTime(Date.now() + 1000)
    rerender()
    expect(result.current.accessToken).toBeNull()
    expect(result.current.auth.session).toBe(fullSession)
    expect(result.current.auth.error).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()).toEqual(token)
    expect(remove).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
    expect(errors).not.toHaveBeenCalled()
    await advance(1000)
    expect(result.current.auth.session).toEqual(anonymousSession)
    expect(result.current.auth.error?.code).toBe('session_expired')
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('keeps expiry transitions on the authenticated request path rather than the token getter', async () => {
    stored(1000)
    const api = adapter()
    const fetcher = mockFetch(() => json({}))
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    vi.setSystemTime(Date.now() + 1000)
    expect(result.current.getAccessToken()).toBeNull()
    expect(result.current.session).toBe(fullSession)
    await act(async () => { await result.current.fetchWithAuth('/api/protected') })
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.error?.code).toBe('session_expired')
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    expect((fetcher.mock.calls[0][0] as Request).headers.has('Authorization')).toBe(false)
  })

  it.each(['cleared', 'expired'] as const)('refresh discards an in-memory credential when canonical storage was externally %s', async state => {
    stored()
    const api = adapter()
    const onError = vi.fn()
    const { result } = renderHook(() => usePocketAuth({ storageKey, api, onError }))
    await settle()
    if (state === 'cleared') sessionStorage.removeItem(storageKey)
    else sessionStorage.setItem(storageKey, JSON.stringify({ accessToken: 'phweb_external', expiresAt: Date.now() - 1 }))
    let finish!: (session: FullSession) => void
    api.session.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    act(() => { void result.current.refresh() })
    await settle()
    expect(result.current.loading).toBe(true)
    expect(result.current.getAccessToken()).toBeNull()
    expect(result.current.session).toEqual(anonymousSession)
    expect(api.session).toHaveBeenLastCalledWith(null, expect.any(AbortSignal))
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    finish(anonymousSession)
    await settle()
    expect(result.current.loading).toBe(false)
    expect(result.current.session).toBe(anonymousSession)
    expect(result.current.getAccessToken()).toBeNull()
    await advance(600000)
    expect(result.current.error).toBeNull()
    expect(onError).not.toHaveBeenCalled()
  })

  it('does not keep an externally cleared credential when anonymous refresh fails', async () => {
    stored()
    const api = adapter()
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    sessionStorage.removeItem(storageKey)
    api.session.mockRejectedValueOnce(new PocketAuthError('request_failed', 'Pocket auth is unavailable. Please try again.'))
    await act(async () => { await result.current.refresh() })
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.getAccessToken()).toBeNull()
    expect(result.current.error?.code).toBe('request_failed')
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
    await advance(600000)
    expect(result.current.error?.code).toBe('request_failed')
  })

  it('retains the full identity on revocation failure and clears it only after confirmed success', async () => {
    const token = stored()
    const api = adapter()
    const { result } = renderHook(() => usePocketAuth({ storageKey, api }))
    await settle()
    api.logout.mockRejectedValueOnce(new PocketAuthError('request_failed', 'Pocket auth is unavailable. Please try again.'))
    await act(async () => { await result.current.signOut() })
    expect(result.current.session).toBe(fullSession)
    expect(result.current.getAccessToken()).toBe(token.accessToken)
    expect(new BrowserTokenStore(storageKey).read()).toEqual(token)
    await act(async () => { await result.current.signOut() })
    expect(result.current.session).toEqual(anonymousSession)
    expect(result.current.getAccessToken()).toBeNull()
    expect(new BrowserTokenStore(storageKey).read()).toBeNull()
  })
})

import { createElement, StrictMode, type ReactNode } from 'react'
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { usePocketAuth } from '../src/usePocketAuth.js'
import { anonymous, editor, grant, issued, json, mockFetch, popup } from './fixtures.js'

const storageKey = 'POCKET_AUTH_SYNTHETIC_TEST'

async function settle() {
  await act(async () => {
    for (let turn = 0; turn < 40; turn++) await Promise.resolve()
  })
}

async function advance(milliseconds: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(milliseconds) })
  await settle()
}

function fixture(override?: (request: Request) => Response | Promise<Response> | undefined) {
  const tabs: ReturnType<typeof popup>[] = []
  const open = vi.spyOn(window, 'open').mockImplementation(() => {
    const tab = popup()
    tabs.push(tab)
    return tab as unknown as Window
  })
  const fetcher = mockFetch(request => {
    const supplied = override?.(request)
    if (supplied !== undefined) return supplied
    switch (new URL(request.url).pathname) {
      case '/api/session': return json(request.headers.has('Authorization') ? editor : anonymous)
      case '/api/session/device/start': return json(grant)
      case '/api/session/device/poll': return json(issued)
      case '/api/session/logout': return json({ logoutUrl: 'http://localhost:8089/auth-service/oauth/device/logout' })
      case '/api/catalog': return json({ suts: [] })
      default: throw new Error('Unexpected fixture request')
    }
  })
  return { tabs, open, fetcher }
}

function stored(accessToken = 'phweb_old', expiresIn = 600000) {
  const value = { accessToken, expiresAt: Date.now() + expiresIn }
  sessionStorage.setItem(storageKey, JSON.stringify(value))
  return value
}

describe('usePocketAuth', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    vi.setSystemTime(new Date('2026-10-19T12:00:00Z'))
    sessionStorage.clear()
    window.history.replaceState(null, '', '/#suts')
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

  it('logs in from an arbitrary preview port without a callback or original-window navigation', async () => {
    const { tabs, open, fetcher } = fixture()
    const onError = vi.fn()
    const { result } = renderHook(() => usePocketAuth({ storageKey, onError }))
    await settle()
    expect(result.current.session).toEqual(anonymous)
    expect(result.current.loading).toBe(false)
    let signIn!: Promise<void>
    act(() => { signIn = result.current.signIn() })
    expect(open).toHaveBeenCalledWith('about:blank', '_blank')
    expect(tabs[0].opener).toBeNull()
    await settle()
    expect(result.current.signingIn).toBe(true)
    expect(result.current.confirmationUrl).toBe(grant.verification_uri_complete)
    expect(tabs[0].location.replace).toHaveBeenCalledWith(grant.verification_uri_complete)
    await advance(4999)
    expect(fetcher.mock.calls.filter(([request]) => new URL((request as Request).url).pathname.endsWith('/poll'))).toHaveLength(0)
    await advance(1)
    await signIn
    expect(result.current.session).toEqual(editor)
    expect(result.current.signingIn).toBe(false)
    expect(result.current.confirmationUrl).toBeNull()
    expect(JSON.parse(sessionStorage.getItem(storageKey)!)).toEqual({ accessToken: issued.access_token, expiresAt: Date.now() + 600000 })
    expect(window.location.href).toBe('http://localhost:49152/#suts')
    expect(fetcher.mock.calls.every(([request]) => new URL((request as Request).url).origin === window.location.origin)).toBe(true)
    expect(onError).not.toHaveBeenCalled()
    await result.current.fetchWithAuth('/api/catalog')
    const latest = fetcher.mock.calls.at(-1)![0] as Request
    expect(latest.headers.get('Authorization')).toBe(`Bearer ${issued.access_token}`)
  })

  it('restores only after fresh identity validation, expires locally and clears its storage', async () => {
    stored('phweb_existing', 10000)
    const { open, fetcher } = fixture()
    const onError = vi.fn()
    const { result } = renderHook(() => usePocketAuth({ storageKey, onError }))
    expect(result.current.session.user).toBeNull()
    await settle()
    expect(result.current.session).toEqual(editor)
    expect((fetcher.mock.calls[0][0] as Request).headers.get('Authorization')).toBe('Bearer phweb_existing')
    expect(open).not.toHaveBeenCalled()
    await advance(10000)
    expect(result.current.session.user).toBeNull()
    expect(result.current.session.canEdit).toBe(false)
    expect(result.current.error?.code).toBe('session_expired')
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('does not send an already expired stored token', async () => {
    stored('phweb_expired', -1000)
    const { fetcher } = fixture()
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    expect(result.current.session).toEqual(anonymous)
    expect((fetcher.mock.calls[0][0] as Request).headers.has('Authorization')).toBe(false)
    expect(sessionStorage.getItem(storageKey)).toBeNull()
  })

  it('clears revoked stored credentials and fails explicitly rather than trusting stale user claims', async () => {
    stored()
    fixture(request => new URL(request.url).pathname === '/api/session' ? json({ error: 'raw secret' }, 401) : undefined)
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    expect(result.current.loading).toBe(false)
    expect(result.current.error?.code).toBe('unauthorized')
    expect(result.current.session.user).toBeNull()
    expect(sessionStorage.getItem(storageKey)).toBeNull()
  })

  it('rejects corrupt stored data without a compatibility credential reader', async () => {
    sessionStorage.setItem(storageKey, '{"tokenType":"Bearer","accessToken":"phweb_old_format"}')
    const { fetcher } = fixture()
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    expect(result.current.error?.code).toBe('invalid_storage')
    expect(fetcher).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(storageKey)).toBeNull()
  })

  it('survives React StrictMode setup/cleanup without duplicate active sessions', async () => {
    stored()
    fixture()
    const onError = vi.fn()
    const wrapper = ({ children }: { children: ReactNode }) => createElement(StrictMode, null, children)
    const { result } = renderHook(() => usePocketAuth({ storageKey, onError }), { wrapper })
    await settle()
    expect(result.current.session).toEqual(editor)
    expect(result.current.loading).toBe(false)
    expect(onError).not.toHaveBeenCalled()
    await advance(0)
    expect(vi.getTimerCount()).toBe(1)
  })

  it('keeps the default controller and credential stable across rerenders', async () => {
    stored()
    const { fetcher } = fixture()
    const { result, rerender } = renderHook(() => usePocketAuth({ storageKey, onError: vi.fn() }))
    await settle()
    const { signIn, refresh, getAccessToken } = result.current
    rerender()
    await settle()
    expect(result.current.signIn).toBe(signIn)
    expect(result.current.refresh).toBe(refresh)
    expect(result.current.getAccessToken).toBe(getAccessToken)
    expect(getAccessToken()).toBe('phweb_old')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(result.current.session).toEqual(editor)
  })

  it.each(['access_denied', 'expired_token'] as const)('stops on %s and never saves a token', async error => {
    const { fetcher, tabs } = fixture(request => new URL(request.url).pathname.endsWith('/poll') ? json({ error }, 400) : undefined)
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    await advance(5000)
    expect(result.current.error?.code).toBe(error)
    expect(result.current.signingIn).toBe(false)
    expect(result.current.confirmationUrl).toBeNull()
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(tabs[0].close).toHaveBeenCalled()
    const count = fetcher.mock.calls.length
    await advance(100000)
    expect(fetcher).toHaveBeenCalledTimes(count)
  })

  it('keeps polling after a blocked popup and exposes a usable confirmation/reopen action', async () => {
    const { open, tabs } = fixture()
    open.mockImplementationOnce(() => null)
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    expect(result.current.error?.code).toBe('popup_blocked')
    expect(result.current.confirmationUrl).toBe(grant.verification_uri_complete)
    expect(result.current.signingIn).toBe(true)
    act(() => result.current.openConfirmation())
    expect(result.current.error).toBeNull()
    expect(tabs[0].location.replace).toHaveBeenCalledWith(grant.verification_uri_complete)
    await advance(5000)
    expect(result.current.session).toEqual(editor)
  })

  it('cancels without waiting for a timer and leaves no pending requests on unmount', async () => {
    const { fetcher } = fixture()
    const { result, unmount } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    act(() => result.current.cancelSignIn())
    expect(result.current.signingIn).toBe(false)
    expect(result.current.confirmationUrl).toBeNull()
    expect(result.current.error).toBeNull()
    const count = fetcher.mock.calls.length
    await advance(10000)
    expect(fetcher).toHaveBeenCalledTimes(count)
    act(() => { void result.current.signIn() })
    await settle()
    unmount()
    await advance(10000)
    expect(fetcher).toHaveBeenCalledTimes(count + 1)
    expect(sessionStorage.getItem(storageKey)).toBeNull()
  })

  it('aborts an in-flight poll on unmount without late credentials or errors', async () => {
    let signal: AbortSignal | null = null
    fixture(request => new URL(request.url).pathname.endsWith('/poll') ? new Promise((_resolve, reject) => {
      signal = request.signal
      signal.addEventListener('abort', () => reject(signal!.reason), { once: true })
    }) : undefined)
    const onError = vi.fn()
    const { result, unmount } = renderHook(() => usePocketAuth({ storageKey, onError }))
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    await advance(5000)
    expect(signal).not.toBeNull()
    unmount()
    await settle()
    expect(signal!.aborted).toBe(true)
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(onError).not.toHaveBeenCalled()
  })

  it('does not let a superseded grant replace the newest confirmation or login', async () => {
    let firstStart!: (response: Response) => void
    let starts = 0
    const { tabs } = fixture(request => {
      if (!new URL(request.url).pathname.endsWith('/start')) return undefined
      starts++
      return starts === 1 ? new Promise(resolve => { firstStart = resolve })
        : json({ ...grant, verification_uri_complete: grant.verification_uri_complete.replace('ABCD-EFGH', 'NEW-CODE') })
    })
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    act(() => { void result.current.signIn() })
    await settle()
    expect(tabs[0].close).toHaveBeenCalled()
    firstStart(json(grant))
    await settle()
    expect(result.current.confirmationUrl).toContain('NEW-CODE')
    expect(tabs[0].location.replace).not.toHaveBeenCalled()
    await advance(5000)
    expect(result.current.session).toEqual(editor)
  })

  it('ignores a late 401 for the previous credential after a newer login', async () => {
    stored()
    let finishOld!: (response: Response) => void
    fixture(request => new URL(request.url).pathname === '/api/slow' ? new Promise(resolve => { finishOld = resolve }) : undefined)
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    const oldRequest = result.current.fetchWithAuth('/api/slow')
    act(() => { void result.current.signIn() })
    await settle()
    await advance(5000)
    finishOld(json({ error: 'old credential revoked' }, 401))
    await act(async () => { await oldRequest })
    expect(result.current.session).toEqual(editor)
    expect(result.current.error).toBeNull()
    expect(JSON.parse(sessionStorage.getItem(storageKey)!).accessToken).toBe(issued.access_token)
  })

  it('clears the current credential on a matching product 401, but never silently reauthenticates', async () => {
    stored()
    const { fetcher } = fixture(request => new URL(request.url).pathname === '/api/catalog' ? json({}, 401) : undefined)
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    await act(async () => { expect((await result.current.fetchWithAuth('/api/catalog')).status).toBe(401) })
    expect(result.current.session.user).toBeNull()
    expect(result.current.error?.code).toBe('unauthorized')
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('reserves the logout tab synchronously, revokes before clearing and leaves the original window alone', async () => {
    const credential = stored()
    let finishLogout!: (response: Response) => void
    const { tabs, open, fetcher } = fixture(request => new URL(request.url).pathname.endsWith('/logout')
      ? new Promise(resolve => { finishLogout = resolve }) : undefined)
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    act(() => { void result.current.signOut() })
    expect(open).toHaveBeenCalledTimes(1)
    await settle()
    expect(result.current.loading).toBe(true)
    expect(sessionStorage.getItem(storageKey)).toBe(JSON.stringify(credential))
    expect(result.current.session).toEqual(editor)
    const request = fetcher.mock.calls.at(-1)![0] as Request
    expect(request.headers.get('Authorization')).toBe('Bearer phweb_old')
    finishLogout(json({ logoutUrl: 'http://localhost:8089/auth-service/oauth/device/logout' }))
    await settle()
    expect(result.current.session.user).toBeNull()
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(result.current.loading).toBe(false)
    expect(tabs[0].location.replace).toHaveBeenCalledWith('http://localhost:8089/auth-service/oauth/device/logout')
    expect(window.location.href).toBe('http://localhost:49152/#suts')
  })

  it('informs the user and retains credentials when revocation fails', async () => {
    const credential = stored()
    const { tabs } = fixture(request => new URL(request.url).pathname.endsWith('/logout') ? json({ error: 'PH unavailable' }, 502) : undefined)
    const onError = vi.fn()
    const { result } = renderHook(() => usePocketAuth({ storageKey, onError }))
    await settle()
    await act(async () => { await result.current.signOut() })
    expect(result.current.error?.code).toBe('request_failed')
    expect(onError).toHaveBeenCalledTimes(1)
    expect(result.current.session).toEqual(editor)
    expect(sessionStorage.getItem(storageKey)).toBe(JSON.stringify(credential))
    expect(tabs[0].close).toHaveBeenCalled()
    expect(tabs[0].location.replace).not.toHaveBeenCalled()
  })

  it('clears locally on successful empty-URL logout without claiming a central logout tab', async () => {
    stored()
    const { tabs } = fixture(request => new URL(request.url).pathname.endsWith('/logout') ? json({ logoutUrl: '' }) : undefined)
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    await act(async () => { await result.current.signOut() })
    expect(result.current.confirmationUrl).toBeNull()
    expect(result.current.session.user).toBeNull()
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(tabs[0].close).toHaveBeenCalled()
  })

  it('exposes a logout link after successful revocation if the PH popup was blocked', async () => {
    stored()
    const { open } = fixture()
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    open.mockImplementationOnce(() => null)
    await act(async () => { await result.current.signOut() })
    expect(result.current.error?.code).toBe('popup_blocked')
    expect(result.current.confirmationUrl).toBe('http://localhost:8089/auth-service/oauth/device/logout')
    expect(result.current.session.user).toBeNull()
    expect(sessionStorage.getItem(storageKey)).toBeNull()
  })

  it('does not expose signed-in identity if persistence of the received token fails', async () => {
    fixture()
    const { result } = renderHook(() => usePocketAuth({ storageKey }))
    await settle()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    act(() => { void result.current.signIn() })
    await settle()
    await advance(5000)
    expect(result.current.error?.code).toBe('storage_unavailable')
    expect(result.current.session.user).toBeNull()
  })
})

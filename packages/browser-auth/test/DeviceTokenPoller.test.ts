import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { pollDeviceToken } from '../src/DeviceTokenPoller.js'
import { parseDeviceAuthorization } from '../src/parseDeviceAuthorization.js'
import { SessionApi } from '../src/SessionApi.js'
import type { DevicePollResult } from '../src/DevicePollResult.js'
import { grant } from './fixtures.js'

describe('DeviceTokenPoller', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-19T12:00:00Z')) })
  afterEach(() => { vi.useRealTimers() })

  it('honors RFC default timing and permanently adds five seconds after slow_down', async () => {
    const poll = vi.fn<SessionApi['poll']>().mockResolvedValueOnce({ kind: 'authorization_pending' })
      .mockResolvedValueOnce({ kind: 'slow_down' }).mockResolvedValueOnce({ kind: 'authorization_pending' })
      .mockResolvedValueOnce({ kind: 'authorized', token: { accessToken: 'phweb_test', expiresAt: Date.now() + 600000 } })
    const result = pollDeviceToken({ poll }, parseDeviceAuthorization(grant, Date.now()), null, new AbortController().signal)
    await vi.advanceTimersByTimeAsync(4999)
    expect(poll).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(poll).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(5000)
    expect(poll).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(9999)
    expect(poll).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(poll).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(10000)
    expect((await result).accessToken).toBe('phweb_test')
    expect(poll).toHaveBeenCalledTimes(4)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('uses the supplied interval and never overlaps a slow poll', async () => {
    const api = new SessionApi()
    let finish!: (value: DevicePollResult) => void
    const poll = vi.spyOn(api, 'poll').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
      .mockResolvedValue({ kind: 'authorized', token: { accessToken: 'phweb_test', expiresAt: Date.now() + 600000 } })
    const result = pollDeviceToken(api, parseDeviceAuthorization({ ...grant, interval: 2 }, Date.now()), null, new AbortController().signal)
    await vi.advanceTimersByTimeAsync(20000)
    expect(poll).toHaveBeenCalledTimes(1)
    finish({ kind: 'authorization_pending' })
    await vi.advanceTimersByTimeAsync(1999)
    expect(poll).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect((await result).accessToken).toBe('phweb_test')
    expect(poll).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each(['access_denied', 'expired_token'] as const)('terminates on %s', async kind => {
    const api = new SessionApi()
    const poll = vi.spyOn(api, 'poll').mockResolvedValue({ kind })
    const result = pollDeviceToken(api, parseDeviceAuthorization(grant, Date.now()), null, new AbortController().signal)
    const check = expect(result).rejects.toMatchObject({ code: kind })
    await vi.advanceTimersByTimeAsync(5000)
    await check
    await vi.advanceTimersByTimeAsync(100000)
    expect(poll).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('expires even while a token request is in flight', async () => {
    const api = new SessionApi()
    let requestSignal: AbortSignal | null = null
    const poll = vi.spyOn(api, 'poll').mockImplementation((_code, _bearer, signal) => new Promise((_resolve, reject) => {
      requestSignal = signal
      signal.addEventListener('abort', () => reject(signal.reason), { once: true })
    }))
    const result = pollDeviceToken(api, parseDeviceAuthorization({ ...grant, expires_in: 7 }, Date.now()), null, new AbortController().signal)
    const check = expect(result).rejects.toMatchObject({ code: 'expired_token' })
    await vi.advanceTimersByTimeAsync(7000)
    await check
    expect(poll).toHaveBeenCalledTimes(1)
    expect(requestSignal!.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('cancels a waiting attempt without issuing a token request', async () => {
    const api = new SessionApi()
    const poll = vi.spyOn(api, 'poll')
    const abort = new AbortController()
    const result = pollDeviceToken(api, parseDeviceAuthorization(grant, Date.now()), null, abort.signal)
    const check = expect(result).rejects.toMatchObject({ name: 'AbortError' })
    abort.abort()
    await check
    await vi.advanceTimersByTimeAsync(10000)
    expect(poll).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })
})

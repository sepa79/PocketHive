import { afterEach, expect, test, vi } from 'vitest'
import { BrowserTokenStore } from '@pockethive/browser-auth'
import { AUTH_STORAGE_KEY, clearAuthSession, readStoredAuthSession, readStoredAccessToken } from './authSession'

afterEach(() => vi.unstubAllGlobals())

test('both browser surfaces retain the same session and logout preserves unrelated selection', () => {
  const storage = new Map<string, string>([['current-workspace', 'chosen']])
  vi.stubGlobal('window', { sessionStorage: {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  } })
  new BrowserTokenStore(AUTH_STORAGE_KEY).write({ accessToken: 'fixture-token', expiresAt: Date.now() + 60_000 })
  expect(readStoredAuthSession()?.accessToken).toBe('fixture-token')
  expect(readStoredAccessToken()).toBe('fixture-token')
  clearAuthSession()
  expect(readStoredAuthSession()).toBeNull()
  expect(storage.get('current-workspace')).toBe('chosen')
})

test('does not expose an expired credential to the TCP surface or API bootstrap', () => {
  const storage = new Map<string, string>([[AUTH_STORAGE_KEY, JSON.stringify({ accessToken: 'expired-token', expiresAt: Date.now() - 1 })]])
  vi.stubGlobal('window', { sessionStorage: {
    getItem: (key: string) => storage.get(key) ?? null,
    removeItem: (key: string) => storage.delete(key),
  } })
  expect(readStoredAuthSession()).toBeNull()
  expect(readStoredAccessToken()).toBeNull()
})

import { afterEach, expect, test, vi } from 'vitest'
import { clearAuthSession, readStoredAuthSession, replaceSessionUser, writeStoredAuthSession } from './authSession'

afterEach(() => vi.unstubAllGlobals())

test('both browser surfaces retain the same session and logout preserves unrelated selection', () => {
  const storage = new Map<string, string>([['current-workspace', 'chosen']])
  vi.stubGlobal('window', { sessionStorage: {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  } })
  const user = { id: 'stable-user', username: 'local-admin', displayName: 'Local Admin', active: true, authProvider: 'DEV', grants: [] }
  writeStoredAuthSession({ accessToken: 'fixture-token', tokenType: 'Bearer', expiresAt: null, user })
  expect(readStoredAuthSession()?.user.id).toBe('stable-user')
  replaceSessionUser({ ...user, displayName: 'Updated' })
  expect(readStoredAuthSession()?.user.displayName).toBe('Updated')
  clearAuthSession()
  expect(readStoredAuthSession()).toBeNull()
  expect(storage.get('current-workspace')).toBe('chosen')
})

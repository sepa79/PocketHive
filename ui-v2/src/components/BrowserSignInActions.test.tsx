import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { AuthProvider } from '../lib/authContext'
import { LoginPage } from '../pages/LoginPage'
import { JournalSessionGate } from './JournalSessionGate'

vi.mock('../lib/controlPlane/bootstrap', () => ({ bootstrapControlPlane: vi.fn(), resetControlPlaneBootstrap: vi.fn() }))
vi.mock('../lib/controlPlane/schemaRegistry', () => ({ resetControlPlaneSchema: vi.fn() }))

const identity = { id: 'synthetic-viewer', username: 'local-viewer', displayName: 'Viewer', active: true, authProvider: 'DEV', grants: [] }
let calls: Request[]
let tab: { opener: unknown; closed: boolean; location: { replace: ReturnType<typeof vi.fn> }; close: ReturnType<typeof vi.fn> }

async function settle() {
  await act(async () => { for (let turn = 0; turn < 50; turn++) await Promise.resolve() })
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
  window.sessionStorage.clear()
  window.history.replaceState(null, '', '/login')
  calls = []
  tab = { opener: {}, closed: false, location: { replace: vi.fn() }, close: vi.fn() }
  vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
  vi.stubGlobal('fetch', vi.fn(async (input: Request | string, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(input, window.location.origin), init)
    calls.push(request)
    let body: unknown
    switch (new URL(request.url).pathname) {
      case '/auth-service/oauth/device_authorization': body = { device_code: 'synthetic-device', user_code: 'ABCD', expires_in: 300,
        verification_uri: 'http://localhost:8089/auth-service/oauth/device_verification',
        verification_uri_complete: 'http://localhost:8089/auth-service/oauth/device_verification?user_code=ABCD' }; break
      case '/auth-service/oauth/token': body = { access_token: 'phweb_synthetic', token_type: 'Bearer', scope: 'pockethive:web', expires_in: 600 }; break
      case '/auth-service/api/auth/me': body = identity; break
      case '/auth-service/api/auth/access': body = { canAccessPocketHive: true, canRunPocketHive: false, canManageDeployment: false, canManageUsers: false }; break
      default: throw new Error('Unexpected synthetic auth request')
    }
    return new Response(JSON.stringify(body))
  }))
})

afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

test('signs into PH on the original page with its real shared device lifecycle', async () => {
  render(<AuthProvider><LoginPage /></AuthProvider>)
  await settle()
  fireEvent.click(screen.getByRole('button', { name: 'Sign in with PocketHive' }))
  await settle()
  expect(tab.opener).toBeNull()
  expect(screen.getByRole('button', { name: 'Open confirmation' })).toBeDefined()
  expect(window.location.pathname).toBe('/login')
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  await settle()
  expect(screen.getByText('Viewer')).toBeDefined()
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeDefined()
  expect(calls.filter(request => new URL(request.url).pathname.endsWith('/oauth/token'))).toHaveLength(1)
})

test('journal defers protected content, preserves the exact run URL and supports cancelling sign-in', async () => {
  window.history.replaceState(null, '', '/journal/swarms/synthetic?runId=exact-run')
  render(<AuthProvider><JournalSessionGate><p>Protected journal fixture</p></JournalSessionGate></AuthProvider>)
  await settle()
  expect(screen.queryByText('Protected journal fixture')).toBeNull()
  expect(calls).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Sign in with PocketHive' }))
  await settle()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel sign-in' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
  expect(calls.filter(request => new URL(request.url).pathname.endsWith('/oauth/token'))).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Sign in with PocketHive' }))
  await settle()
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  await settle()
  expect(screen.getByText('Protected journal fixture')).toBeDefined()
  expect(window.location.pathname + window.location.search).toBe('/journal/swarms/synthetic?runId=exact-run')
})

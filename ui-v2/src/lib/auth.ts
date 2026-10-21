import { readStoredAccessToken } from './authSession'
import { normalizeUser } from './authIdentity'
import type { AuthGrant, AuthenticatedUser } from './authIdentity'
export { readStoredAccessToken } from './authSession'
export type { AuthGrant, AuthenticatedUser } from './authIdentity'

export type UserUpsertRequest = {
  username: string
  displayName: string
  active: boolean
}

export type UserGrantsReplaceRequest = {
  grants: AuthGrant[]
}

type ApiError = Error & { status?: number }

const AUTH_PREFIXES = ['/scenario-manager/', '/orchestrator/', '/network-proxy-manager/', '/auth-service/']
const AUTH_EXCLUDED_PATHS = new Set(['/auth-service/api/auth/dev/login', '/auth-service/oauth/token', '/auth-service/oauth/revoke', '/auth-service/oauth/device_authorization'])

let authenticatedFetchInstalled = false

async function ensureOk(response: Response, fallback: string) {
  if (response.ok) return
  let message = ''
  try {
    const text = await response.text()
    if (text) {
      try {
        const payload = JSON.parse(text) as { message?: unknown }
        if (typeof payload.message === 'string' && payload.message.trim()) {
          message = payload.message.trim()
        } else {
          message = text
        }
      } catch {
        message = text
      }
    }
  } catch {
    // ignore
  }
  const error: ApiError = new Error(message || fallback)
  error.status = response.status
  throw error
}

export async function listAdminUsers(): Promise<AuthenticatedUser[]> {
  const response = await fetch('/auth-service/api/auth/admin/users', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load users')
  const payload = await response.json()
  if (!Array.isArray(payload)) {
    throw new Error('Auth service returned invalid users payload')
  }
  return payload.map((entry) => normalizeUser(entry)).filter((entry): entry is AuthenticatedUser => entry !== null)
}

export async function upsertAdminUser(userId: string, request: UserUpsertRequest): Promise<AuthenticatedUser> {
  const response = await fetch(`/auth-service/api/auth/admin/users/${encodeURIComponent(userId)}`, {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  })
  await ensureOk(response, 'Failed to save user')
  const user = normalizeUser(await response.json())
  if (!user) throw new Error('Auth service returned invalid user payload')
  return user
}

export async function replaceAdminUserGrants(userId: string, request: UserGrantsReplaceRequest): Promise<AuthenticatedUser> {
  const response = await fetch(`/auth-service/api/auth/admin/users/${encodeURIComponent(userId)}/grants`, {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  })
  await ensureOk(response, 'Failed to save grants')
  const user = normalizeUser(await response.json())
  if (!user) throw new Error('Auth service returned invalid user payload')
  return user
}

function shouldAttachAuth(url: URL): boolean {
  if (typeof window === 'undefined') return false
  if (url.origin !== window.location.origin) return false
  if (AUTH_EXCLUDED_PATHS.has(url.pathname)) return false
  return AUTH_PREFIXES.some((prefix) => url.pathname.startsWith(prefix))
}

function withAuthorizationHeader(input: RequestInfo | URL, init: RequestInit | undefined, token: string) {
  if (input instanceof Request) {
    const headers = new Headers(input.headers)
    if (!headers.has('Authorization')) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    return { input: new Request(input, { headers }), init: undefined }
  }

  const headers = new Headers(init?.headers ?? undefined)
  if (!headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  return { input, init: { ...init, headers } }
}

export function installAuthenticatedFetch() {
  if (authenticatedFetchInstalled || typeof window === 'undefined') return
  const nativeFetch = window.fetch.bind(window)

  window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(input instanceof Request ? input.url : input.toString(), window.location.origin)
    if (!shouldAttachAuth(url)) {
      return nativeFetch(input, init)
    }

    const token = readStoredAccessToken()
    if (!token) {
      return nativeFetch(input, init)
    }

    const request = withAuthorizationHeader(input, init, token)
    return nativeFetch(request.input, request.init)
  }) as typeof window.fetch

  authenticatedFetchInstalled = true
}

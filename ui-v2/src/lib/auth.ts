import {
  AuthProducts,
  PocketHivePermissionIds,
  PocketHiveResourceSelectors,
  PocketHiveResourceTypes,
} from './authContracts'

export type AuthGrant = {
  product: string
  permission: string
  resourceType: string
  resourceSelector: string
}

export type AuthGrantMatch = Partial<AuthGrant>

export type AuthenticatedUser = {
  id: string
  username: string
  displayName: string
  active: boolean
  authProvider: string
  grants: AuthGrant[]
}

export type AuthSession = {
  accessToken: string
  tokenType: string
  expiresAt: string | null
  user: AuthenticatedUser
}

export type UserUpsertRequest = {
  username: string
  displayName: string
  active: boolean
}

export type UserGrantsReplaceRequest = {
  grants: AuthGrant[]
}

type PocketHiveResourceAccess = {
  bundlePath?: string | null
  folderPath?: string | null
}

type ApiError = Error & { status?: number }

const AUTH_SESSION_KEY = 'PH_UI_V2_AUTH_SESSION'
const AUTH_PREFIXES = ['/scenario-manager/', '/orchestrator/', '/network-proxy-manager/', '/auth-service/']
const AUTH_EXCLUDED_PATHS = new Set(['/auth-service/api/auth/dev/login'])

let authenticatedFetchInstalled = false

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function normalizeGrant(input: unknown): AuthGrant | null {
  if (!isRecord(input)) return null
  const product = asString(input.product)
  const permission = asString(input.permission)
  const resourceType = asString(input.resourceType)
  const resourceSelector = asString(input.resourceSelector)
  if (!product || !permission || !resourceType || !resourceSelector) return null
  return { product, permission, resourceType, resourceSelector }
}

function normalizeUser(input: unknown): AuthenticatedUser | null {
  if (!isRecord(input)) return null
  const id = asString(input.id)
  const username = asString(input.username)
  if (!id || !username) return null
  const grants = Array.isArray(input.grants)
    ? input.grants.map((entry) => normalizeGrant(entry)).filter((entry): entry is AuthGrant => entry !== null)
    : []
  return {
    id,
    username,
    displayName: asString(input.displayName) ?? username,
    active: input.active !== false,
    authProvider: asString(input.authProvider) ?? 'UNKNOWN',
    grants,
  }
}

function normalizeSession(input: unknown): AuthSession | null {
  if (!isRecord(input)) return null
  const accessToken = asString(input.accessToken)
  const tokenType = asString(input.tokenType)
  const user = normalizeUser(input.user)
  if (!accessToken || !tokenType || !user) return null
  return {
    accessToken,
    tokenType,
    expiresAt: asString(input.expiresAt),
    user,
  }
}

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

export function readStoredAuthSession(): AuthSession | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.sessionStorage.getItem(AUTH_SESSION_KEY)
    if (!raw) return null
    return normalizeSession(JSON.parse(raw))
  } catch {
    return null
  }
}

export function writeStoredAuthSession(session: AuthSession | null) {
  if (typeof window === 'undefined') return
  try {
    if (!session) {
      window.sessionStorage.removeItem(AUTH_SESSION_KEY)
      return
    }
    window.sessionStorage.setItem(AUTH_SESSION_KEY, JSON.stringify(session))
  } catch {
    // ignore
  }
}

export function readStoredAccessToken(): string | null {
  return readStoredAuthSession()?.accessToken ?? null
}

export async function loginDevUser(username: string): Promise<AuthSession> {
  const trimmed = username.trim()
  const response = await fetch('/auth-service/api/auth/dev/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ username: trimmed }),
  })
  await ensureOk(response, 'Dev login failed')
  const session = normalizeSession(await response.json())
  if (!session) throw new Error('Auth service returned invalid session payload')
  writeStoredAuthSession(session)
  return session
}

export async function fetchCurrentUser(accessToken: string): Promise<AuthenticatedUser> {
  const response = await fetch('/auth-service/api/auth/me', {
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
  })
  await ensureOk(response, 'Failed to resolve current user')
  const user = normalizeUser(await response.json())
  if (!user) throw new Error('Auth service returned invalid user payload')
  return user
}

export function grantMatches(grant: AuthGrant, match: AuthGrantMatch): boolean {
  if (match.product && grant.product !== match.product) return false
  if (match.permission && grant.permission !== match.permission) return false
  if (match.resourceType && grant.resourceType !== match.resourceType) return false
  if (match.resourceSelector && grant.resourceSelector !== match.resourceSelector) return false
  return true
}

export function userHasGrant(user: AuthenticatedUser | null, match: AuthGrantMatch): boolean {
  if (!user) return false
  return user.grants.some((grant) => grantMatches(grant, match))
}

function normalizeResourcePath(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function isPocketHiveGrant(grant: AuthGrant, permissions: readonly string[]): boolean {
  return grant.product === AuthProducts.POCKETHIVE && permissions.includes(grant.permission)
}

function matchesFolderSelector(selector: string, folderPath: string | null): boolean {
  if (!selector || !folderPath) return false
  return folderPath === selector || folderPath.startsWith(`${selector}/`)
}

function matchesPocketHiveScope(grant: AuthGrant, access?: PocketHiveResourceAccess): boolean {
  const bundlePath = normalizeResourcePath(access?.bundlePath)
  const folderPath = normalizeResourcePath(access?.folderPath)

  if (grant.resourceType === PocketHiveResourceTypes.DEPLOYMENT) {
    return grant.resourceSelector === PocketHiveResourceSelectors.GLOBAL
  }
  if (grant.resourceType === PocketHiveResourceTypes.FOLDER) {
    return matchesFolderSelector(grant.resourceSelector, folderPath)
  }
  if (grant.resourceType === PocketHiveResourceTypes.BUNDLE) {
    return bundlePath !== null && grant.resourceSelector === bundlePath
  }
  return false
}

export function userHasAnyPocketHivePermission(user: AuthenticatedUser | null, permissions: readonly string[]): boolean {
  if (!user) return false
  return user.grants.some((grant) => isPocketHiveGrant(grant, permissions))
}

export function userHasPocketHivePermissionInScope(
  user: AuthenticatedUser | null,
  permissions: readonly string[],
  access?: PocketHiveResourceAccess,
): boolean {
  if (!user) return false
  return user.grants.some((grant) => isPocketHiveGrant(grant, permissions) && matchesPocketHiveScope(grant, access))
}

export function userCanViewPocketHive(user: AuthenticatedUser | null): boolean {
  return userHasAnyPocketHivePermission(user, [
    PocketHivePermissionIds.VIEW,
    PocketHivePermissionIds.RUN,
    PocketHivePermissionIds.ALL,
  ])
}

export function userCanRunAnywhere(user: AuthenticatedUser | null): boolean {
  return userHasAnyPocketHivePermission(user, [PocketHivePermissionIds.RUN, PocketHivePermissionIds.ALL])
}

export function userCanManagePocketHive(user: AuthenticatedUser | null): boolean {
  return userHasAnyPocketHivePermission(user, [PocketHivePermissionIds.ALL])
}

export function userCanViewPocketHiveResource(user: AuthenticatedUser | null, access?: PocketHiveResourceAccess): boolean {
  return userHasPocketHivePermissionInScope(
    user,
    [PocketHivePermissionIds.VIEW, PocketHivePermissionIds.RUN, PocketHivePermissionIds.ALL],
    access,
  )
}

export function userCanRunPocketHiveResource(user: AuthenticatedUser | null, access?: PocketHiveResourceAccess): boolean {
  return userHasPocketHivePermissionInScope(
    user,
    [PocketHivePermissionIds.RUN, PocketHivePermissionIds.ALL],
    access,
  )
}

export function userCanManagePocketHiveResource(user: AuthenticatedUser | null, access?: PocketHiveResourceAccess): boolean {
  return userHasPocketHivePermissionInScope(user, [PocketHivePermissionIds.ALL], access)
}

export function userCanManagePocketHiveFolder(user: AuthenticatedUser | null, folderPath: string | null | undefined): boolean {
  return userCanManagePocketHiveResource(user, { folderPath })
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

export function clearAuthSession() {
  writeStoredAuthSession(null)
}

export function replaceSessionUser(user: AuthenticatedUser) {
  const session = readStoredAuthSession()
  if (!session) return
  writeStoredAuthSession({ ...session, user })
}

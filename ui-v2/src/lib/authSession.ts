/**
 * Responsibility: own the PocketHive browser session contract and session-storage access.
 * Must not: grant permissions, resolve identity or implement another login.
 * Contract: docs/tcp-mock/legacy-workspaces.md#authentication-provider-and-ownership.
 */
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

const AUTH_SESSION_KEY = 'PH_UI_V2_AUTH_SESSION'

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

export function normalizeUser(input: unknown): AuthenticatedUser | null {
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

export function normalizeSession(input: unknown): AuthSession | null {
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

export function clearAuthSession() {
  writeStoredAuthSession(null)
}

export function replaceSessionUser(user: AuthenticatedUser) {
  const session = readStoredAuthSession()
  if (!session) return
  writeStoredAuthSession({ ...session, user })
}

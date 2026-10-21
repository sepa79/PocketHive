export type AuthGrant = {
  product: string
  permission: string
  resourceType: string
  resourceSelector: string
}

export type AuthenticatedUser = {
  id: string
  username: string
  displayName: string
  active: boolean
  authProvider: string
  grants: AuthGrant[]
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

/**
 * Responsibility: decode PH's current-user identity projection.
 * Must not: store credentials or derive product permissions from grants.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export function normalizeUser(value: unknown): AuthenticatedUser | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const user = value as Record<string, unknown>
  const id = text(user.id)
  const username = text(user.username)
  if (!id || !username || typeof user.active !== 'boolean' || !Array.isArray(user.grants)) return null
  const grants: AuthGrant[] = []
  for (const supplied of user.grants) {
    if (typeof supplied !== 'object' || supplied === null || Array.isArray(supplied)) return null
    const grant = supplied as Record<string, unknown>
    const product = text(grant.product)
    const permission = text(grant.permission)
    const resourceType = text(grant.resourceType)
    const resourceSelector = text(grant.resourceSelector)
    if (!product || !permission || !resourceType || !resourceSelector) return null
    grants.push({ product, permission, resourceType, resourceSelector })
  }
  const displayName = text(user.displayName)
  const authProvider = text(user.authProvider)
  if (!displayName || !authProvider) return null
  return { id, username, displayName, active: user.active, authProvider, grants }
}

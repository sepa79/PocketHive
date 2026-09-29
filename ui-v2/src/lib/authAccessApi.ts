import type { AuthAccessView } from './AuthAccessView'

/**
 * Responsibility: load and validate AuthAccessView through public ingress.
 * Must not: interpret grants or replace failed observations with permissions.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export async function authAccessApi(token: string): Promise<AuthAccessView> {
  const response = await fetch('/auth-service/api/auth/access', {
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}` }, cache: 'no-store',
  })
  if (!response.ok) throw new Error(`Failed to load permissions: HTTP ${response.status}`)
  const value: unknown = await response.json()
  if (!value || typeof value !== 'object' ||
      !('canAccessPocketHive' in value) || typeof value.canAccessPocketHive !== 'boolean' ||
      !('canRunPocketHive' in value) || typeof value.canRunPocketHive !== 'boolean' ||
      !('canManageUsers' in value) || typeof value.canManageUsers !== 'boolean') {
    throw new Error('Invalid permissions response')
  }
  return { canAccessPocketHive: value.canAccessPocketHive, canRunPocketHive: value.canRunPocketHive, canManageUsers: value.canManageUsers }
}

import { PocketAuthError } from './PocketAuthError.js'
import type { PocketSession } from './PocketSession.js'
import { responseObject, responseString } from './responseValues.js'

/**
 * Responsibility: decode the authoritative backend session projection.
 * Must not: derive editing access from names, roles, token claims or local state.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function parseSession(value: unknown): PocketSession {
  const body = responseObject(value)
  if (typeof body.canEdit !== 'boolean' || typeof body.configured !== 'boolean') {
    throw new PocketAuthError('protocol', 'Invalid Pocket session response.')
  }
  let user: PocketSession['user'] = null
  if (body.user !== null) {
    const suppliedUser = responseObject(body.user)
    user = { username: responseString(suppliedUser.username), displayName: responseString(suppliedUser.displayName) }
  }
  if (user === null && body.canEdit) throw new PocketAuthError('protocol', 'Invalid Pocket session response.')
  return { user, canEdit: body.canEdit, configured: body.configured }
}

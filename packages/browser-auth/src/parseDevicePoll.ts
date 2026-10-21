import type { DevicePollResult } from './DevicePollResult.js'
import { PocketAuthError } from './PocketAuthError.js'
import { bearerToken, expiryFromSeconds, responseObject, responseString } from './responseValues.js'

/**
 * Responsibility: decode the device-token success or documented polling outcomes.
 * Must not: retry unknown failures, trust claims as identity or expose raw error bodies.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function parseDevicePoll(value: unknown, status: number, requestedAt: number): DevicePollResult {
  const body = responseObject(value)
  if (status === 400) {
    switch (body.error) {
      case 'authorization_pending':
      case 'slow_down':
      case 'access_denied':
      case 'expired_token':
        return { kind: body.error }
      default:
        throw new PocketAuthError('request_failed', 'PH rejected the sign-in request. Please start again.')
    }
  }
  if (status !== 200 || typeof body.token_type !== 'string' || body.token_type.toLowerCase() !== 'bearer') {
    throw new PocketAuthError('protocol', 'Invalid Pocket token response.')
  }
  responseString(body.scope)
  return { kind: 'authorized', token: {
    accessToken: bearerToken(body.access_token), expiresAt: expiryFromSeconds(body.expires_in, requestedAt),
  } }
}

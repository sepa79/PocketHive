import type { DeviceAuthorization } from './DeviceAuthorization.js'
import { PocketAuthError } from './PocketAuthError.js'
import { confirmationUrl } from './confirmationUrl.js'
import { expiryFromSeconds, millisecondsFromSeconds, responseObject, responseString } from './responseValues.js'
import { DEFAULT_POLL_INTERVAL_MS } from './sessionProtocol.js'

/**
 * Responsibility: decode a device grant and its public PH verification destination.
 * Must not: persist the device secret, issue tokens or manufacture callback URLs.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function parseDeviceAuthorization(value: unknown, requestedAt: number): DeviceAuthorization {
  const body = responseObject(value)
  const verificationUrl = confirmationUrl(body.verification_uri_complete)
  const base = confirmationUrl(body.verification_uri)
  if (new URL(base).origin !== new URL(verificationUrl).origin) {
    throw new PocketAuthError('protocol', 'PH verification URLs must share an origin.')
  }
  return {
    deviceCode: responseString(body.device_code),
    userCode: responseString(body.user_code),
    verificationUrl,
    expiresAt: expiryFromSeconds(body.expires_in, requestedAt),
    intervalMs: body.interval === undefined ? DEFAULT_POLL_INTERVAL_MS : millisecondsFromSeconds(body.interval),
  }
}

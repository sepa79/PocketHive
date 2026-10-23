import type { DeviceAuthorization } from './DeviceAuthorization.js'
import type { SessionApiPort } from './SessionApiPort.js'
import type { StoredToken } from './StoredToken.js'
import { PocketAuthError } from './PocketAuthError.js'
import { scheduleDeadline } from './scheduleDeadline.js'
import { SLOW_DOWN_INCREMENT_MS } from './sessionProtocol.js'
import { waitUntil } from './waitUntil.js'

/**
 * Responsibility: own the single-flight RFC device polling schedule and terminal outcomes.
 * Must not: persist tokens, expose device secrets, determine permissions or manipulate tabs.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export async function pollDeviceToken(api: Pick<SessionApiPort, 'poll'>, grant: DeviceAuthorization, bearer: string | null, signal: AbortSignal): Promise<StoredToken> {
  const expiry = new AbortController()
  const expired = new PocketAuthError('expired_token', 'PH sign-in expired. Please start again.')
  const cancelExpiry = scheduleDeadline(grant.expiresAt, () => expiry.abort(expired))
  const combined = AbortSignal.any([signal, expiry.signal])
  let interval = grant.intervalMs
  try {
    while (true) {
      await waitUntil(Date.now() + interval, combined)
      combined.throwIfAborted()
      const result = await api.poll(grant.deviceCode, bearer, combined)
      combined.throwIfAborted()
      switch (result.kind) {
        case 'authorized': return result.token
        case 'slow_down': interval += SLOW_DOWN_INCREMENT_MS; break
        case 'authorization_pending': break
        case 'access_denied': throw new PocketAuthError('access_denied', 'PH sign-in was denied. No session was created.')
        case 'expired_token': throw expired
      }
    }
  } finally {
    cancelExpiry()
  }
}

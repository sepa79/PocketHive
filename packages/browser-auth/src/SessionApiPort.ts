import type { DeviceAuthorization } from './DeviceAuthorization.js'
import type { DevicePollResult } from './DevicePollResult.js'
import type { PocketSession } from './PocketSession.js'

/**
 * Responsibility: define explicit wire operations and a typed anonymous session projection.
 * Must not: own credentials, token lifetime, polling schedules or product permissions.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export interface SessionApiPort<Session extends PocketSession = PocketSession> {
  readonly anonymousSession: Session & { user: null; canEdit: false }
  session(token: string | null, signal: AbortSignal): Promise<Session>
  start(token: string | null, signal: AbortSignal): Promise<DeviceAuthorization>
  poll(deviceCode: string, token: string | null, signal: AbortSignal): Promise<DevicePollResult>
  logout(token: string | null, signal: AbortSignal): Promise<string>
}

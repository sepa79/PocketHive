import { confirmationUrl } from './confirmationUrl.js'
import { parseDeviceAuthorization } from './parseDeviceAuthorization.js'
import { parseDevicePoll } from './parseDevicePoll.js'
import { parseSession } from './parseSession.js'
import type { PocketSession } from './PocketSession.js'
import { responseObject } from './responseValues.js'
import type { SessionApiPort } from './SessionApiPort.js'
import { SessionHttpClient } from './SessionHttpClient.js'
import { DEVICE_POLL_PATH, DEVICE_START_PATH, LOGOUT_PATH, SESSION_PATH } from './sessionProtocol.js'

/**
 * Responsibility: map the default same-origin session/device wire contract.
 * Must not: own browser credentials, schedule polls, issue tokens or choose product permissions.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export class SessionApi implements SessionApiPort<PocketSession> {
  readonly anonymousSession: PocketSession & { user: null; canEdit: false } = { user: null, canEdit: false, configured: false }
  private readonly http = new SessionHttpClient()

  async session(token: string | null, signal: AbortSignal) {
    const result = await this.http.request(SESSION_PATH, { method: 'GET' }, token, signal)
    return parseSession(result.body)
  }

  async start(token: string | null, signal: AbortSignal) {
    const result = await this.post(DEVICE_START_PATH, {}, token, signal)
    return parseDeviceAuthorization(result.body, result.requestedAt)
  }

  async poll(deviceCode: string, token: string | null, signal: AbortSignal) {
    const result = await this.post(DEVICE_POLL_PATH, { deviceCode }, token, signal, true)
    return parseDevicePoll(result.body, result.status, result.requestedAt)
  }

  async logout(token: string | null, signal: AbortSignal): Promise<string> {
    const result = await this.post(LOGOUT_PATH, {}, token, signal)
    const body = responseObject(result.body)
    if (body.logoutUrl === '') return ''
    return confirmationUrl(body.logoutUrl)
  }

  private post(path: string, body: object, token: string | null, signal: AbortSignal, polling = false) {
    return this.http.request(path, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    }, token, signal, { polling })
  }
}

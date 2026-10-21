import { confirmationUrl, parseDeviceAuthorization, parseDevicePoll, PocketAuthError, SessionHttpClient,
  type SessionApiPort } from '@pockethive/browser-auth'
import { normalizeUser } from './authIdentity'
import type { PocketHiveSession } from './PocketHiveSession'

const clientId = 'pockethive-device'
const resource = 'urn:pockethive:web'
const scope = 'pockethive:web'
const prefix = '/auth-service'

/**
 * Responsibility: map PH OAuth and identity endpoints to the shared browser-auth port.
 * Must not: persist credentials, schedule polls, issue tokens or decide product access.
 * Contract: RESP-BROWSER-DEVICE-LOGIN — docs/architecture/runtime-responsibilities.md#resp-browser-device-login.
 */
export class PocketHiveSessionApi implements SessionApiPort<PocketHiveSession> {
  readonly anonymousSession: PocketHiveSession & { user: null; canEdit: false } = { user: null, canEdit: false, configured: true }
  private readonly http = new SessionHttpClient()

  async session(token: string | null, signal: AbortSignal): Promise<PocketHiveSession> {
    if (token === null) return this.anonymousSession
    const result = await this.http.request(`${prefix}/api/auth/me`, { method: 'GET' }, token, signal)
    const user = normalizeUser(result.body)
    if (!user || !user.active) throw new PocketAuthError('protocol', 'PH did not confirm an active identity.')
    return { user, canEdit: false, configured: true }
  }

  async start(token: string | null, signal: AbortSignal) {
    const result = await this.http.request(`${prefix}/oauth/device_authorization`, this.form({ client_id: clientId, resource, scope }), token, signal)
    return parseDeviceAuthorization(result.body, result.requestedAt)
  }

  async poll(deviceCode: string, token: string | null, signal: AbortSignal) {
    const result = await this.http.request(`${prefix}/oauth/token`, this.form({
      client_id: clientId, resource, device_code: deviceCode, grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
    }), token, signal, { polling: true })
    const parsed = parseDevicePoll(result.body, result.status, result.requestedAt)
    if (parsed.kind === 'authorized' && (result.body as Record<string, unknown>).scope !== scope) {
      throw new PocketAuthError('protocol', 'Invalid PH browser scope.')
    }
    return parsed
  }

  async logout(token: string | null, signal: AbortSignal): Promise<string> {
    const config = await this.http.request(`${prefix}/api/auth/device/config?client_id=${clientId}`, { method: 'GET' }, token, signal)
    if (typeof config.body !== 'object' || config.body === null || Array.isArray(config.body)) {
      throw new PocketAuthError('protocol', 'Invalid PH sign-out configuration.')
    }
    const url = confirmationUrl((config.body as Record<string, unknown>).logoutUri)
    if (token !== null) {
      await this.http.request(`${prefix}/oauth/revoke`, this.form({ client_id: clientId, token, token_type_hint: 'access_token' }), null, signal, { empty: true })
    }
    return url
  }

  private form(parameters: Record<string, string>): RequestInit {
    return { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(parameters) }
  }
}

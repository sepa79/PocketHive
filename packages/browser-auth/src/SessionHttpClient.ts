import { authenticatedRequest } from './authenticatedRequest.js'
import { PocketAuthError } from './PocketAuthError.js'
import { readSessionJson } from './readSessionJson.js'
import { REQUEST_TIMEOUT_MS } from './sessionProtocol.js'

/**
 * Responsibility: perform bounded same-origin auth HTTP I/O with safe failures.
 * Must not: map identity or device outcomes, store credentials, retry or schedule polls.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export class SessionHttpClient {
  async request(path: string, init: RequestInit, token: string | null, signal: AbortSignal,
    options: { polling?: boolean; empty?: boolean } = {}): Promise<{ body: unknown; status: number; requestedAt: number }> {
    const timeout = new AbortController()
    const timer = setTimeout(() => timeout.abort(), REQUEST_TIMEOUT_MS)
    const combined = AbortSignal.any([signal, timeout.signal])
    const requestedAt = Date.now()
    try {
      const response = await fetch(authenticatedRequest(path, {
        ...init, cache: 'no-store', signal: combined,
      }, token))
      if (response.status === 401) throw new PocketAuthError('unauthorized', 'Your Pocket session is no longer valid. Sign in again.')
      if (!response.ok && !(options.polling && response.status === 400)) {
        throw new PocketAuthError('request_failed', 'Pocket auth is unavailable. Please try again.')
      }
      return { body: options.empty && response.ok ? null : await readSessionJson(response), status: response.status, requestedAt }
    } catch (error) {
      if (signal.aborted) throw signal.reason
      if (timeout.signal.aborted) throw new PocketAuthError('request_timeout', 'Pocket auth did not respond in time. Please try again.')
      if (error instanceof PocketAuthError) throw error
      throw new PocketAuthError('request_failed', 'Could not reach Pocket auth. Please try again.')
    } finally {
      clearTimeout(timer)
    }
  }
}

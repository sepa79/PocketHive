export type PocketAuthErrorCode = 'protocol' | 'request_failed' | 'request_timeout'
  | 'popup_blocked' | 'access_denied' | 'expired_token' | 'session_expired'
  | 'storage_unavailable' | 'invalid_storage' | 'cross_origin' | 'unauthorized'

/**
 * Responsibility: expose safe, typed browser-auth failures without server payloads.
 * Must not: retain bearer/device codes, raw responses or product permission rules.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export class PocketAuthError extends Error {
  constructor(readonly code: PocketAuthErrorCode, message: string) {
    super(message)
    this.name = 'PocketAuthError'
  }
}

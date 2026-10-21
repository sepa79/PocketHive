import type { StoredToken } from './StoredToken.js'
import { PocketAuthError } from './PocketAuthError.js'
import { bearerToken, responseObject } from './responseValues.js'

/**
 * Responsibility: read and write one tab-scoped access credential with local expiry.
 * Must not: persist device codes, identity projections, product access or login attempts.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export class BrowserTokenStore {
  constructor(private readonly key: string) {
    if (!key.trim()) throw new Error('Pocket auth requires a nonempty storageKey.')
  }

  read(): StoredToken | null {
    let raw: string | null
    try {
      raw = window.sessionStorage.getItem(this.key)
    } catch {
      throw new PocketAuthError('storage_unavailable', 'Session storage is unavailable. Allow it to sign in.')
    }
    if (raw === null) return null
    try {
      const value = responseObject(JSON.parse(raw))
      const accessToken = bearerToken(value.accessToken)
      if (typeof value.expiresAt !== 'number' || !Number.isSafeInteger(value.expiresAt)
          || value.expiresAt <= 0 || value.expiresAt > 8_640_000_000_000_000) throw new Error()
      return { accessToken, expiresAt: value.expiresAt }
    } catch {
      this.write(null)
      throw new PocketAuthError('invalid_storage', 'Stored sign-in is invalid. Please sign in again.')
    }
  }

  write(token: StoredToken | null): void {
    try {
      if (token === null) window.sessionStorage.removeItem(this.key)
      else window.sessionStorage.setItem(this.key, JSON.stringify(token))
    } catch {
      throw new PocketAuthError('storage_unavailable', 'Session storage is unavailable. Allow it to sign in.')
    }
  }
}

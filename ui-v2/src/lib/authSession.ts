import { BrowserTokenStore } from '@pockethive/browser-auth'

export const AUTH_STORAGE_KEY = 'PH_DEVICE_SESSION'
const store = new BrowserTokenStore(AUTH_STORAGE_KEY)

/**
 * Responsibility: project the shared credential for PH API bootstrap and the TCP UI.
 * Must not: parse another session format, cache identity or initiate login.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
export function readStoredAuthSession() {
  const token = store.read()
  return token !== null && token.expiresAt > Date.now() ? token : null
}

export function readStoredAccessToken(): string | null {
  return readStoredAuthSession()?.accessToken ?? null
}

export function clearAuthSession(): void {
  store.write(null)
}

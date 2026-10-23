import { PocketAuthError } from './PocketAuthError.js'

/**
 * Responsibility: validate primitive values shared by session protocol decoders.
 * Must not: infer identity, keep credentials, accept wire errors as messages or schedule time.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function responseObject(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new PocketAuthError('protocol', 'Invalid Pocket auth response.')
  }
  return value as Record<string, unknown>
}

export function responseString(value: unknown): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new PocketAuthError('protocol', 'Invalid Pocket auth response.')
  }
  return value
}

export function millisecondsFromSeconds(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0
      || !Number.isSafeInteger(value * 1000)) {
    throw new PocketAuthError('protocol', 'Invalid Pocket auth expiry or polling interval.')
  }
  return value * 1000
}

export function expiryFromSeconds(value: unknown, requestedAt: number): number {
  const expiry = requestedAt + millisecondsFromSeconds(value)
  if (!Number.isSafeInteger(expiry) || expiry > 8_640_000_000_000_000) {
    throw new PocketAuthError('protocol', 'Invalid Pocket auth expiry.')
  }
  return expiry
}

export function bearerToken(value: unknown): string {
  const token = responseString(value)
  if (!/^[A-Za-z0-9\-._~+/]+=*$/.test(token)) {
    throw new PocketAuthError('protocol', 'Invalid Pocket auth credential.')
  }
  return token
}

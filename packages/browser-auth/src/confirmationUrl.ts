import { PocketAuthError } from './PocketAuthError.js'
import { responseString } from './responseValues.js'

/**
 * Responsibility: validate public PH navigation links at the browser boundary.
 * Must not: select PH hosts, accept secret-bearing URLs or infer redirect callbacks.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function confirmationUrl(value: unknown): string {
  let target: URL
  try {
    target = new URL(responseString(value))
  } catch {
    throw new PocketAuthError('protocol', 'Invalid PH confirmation URL.')
  }
  const secrets = new Set(['access_token', 'device_code', 'refresh_token', 'id_token', 'token', 'code'])
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password || target.hash
      || [...target.searchParams.keys()].some(key => secrets.has(key.toLowerCase()))) {
    throw new PocketAuthError('protocol', 'Invalid PH confirmation URL.')
  }
  return target.href
}

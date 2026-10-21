import { PocketAuthError } from './PocketAuthError.js'

/**
 * Responsibility: attach the owned bearer only to same-origin, nonredirecting requests.
 * Must not: send credentials to PH/external URLs directly or accept caller authorization overrides.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function authenticatedRequest(input: RequestInfo | URL, init: RequestInit | undefined, token: string | null): Request {
  const requestedUrl = input instanceof Request ? input.url : String(input)
  const target = new URL(requestedUrl, window.location.origin)
  if (target.origin !== window.location.origin || target.username || target.password) {
    throw new PocketAuthError('cross_origin', 'Authenticated requests must stay on this application origin.')
  }
  const request = new Request(input instanceof Request ? input : target, init)
  const headers = new Headers(request.headers)
  headers.delete('Authorization')
  if (token !== null) headers.set('Authorization', `Bearer ${token}`)
  return new Request(request, { headers, redirect: 'error', credentials: 'same-origin' })
}

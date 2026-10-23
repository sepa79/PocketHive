import { PocketAuthError } from './PocketAuthError.js'
import { MAX_JSON_BYTES } from './sessionProtocol.js'

/**
 * Responsibility: read bounded JSON from the session API without retaining raw error payloads.
 * Must not: map protocol outcomes, log bodies or permit unbounded response buffering.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export async function readSessionJson(response: Response): Promise<unknown> {
  if (!response.body) throw new PocketAuthError('protocol', 'Missing Pocket auth response.')
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let received = 0
  let body = ''
  try {
    while (true) {
      const chunk = await reader.read()
      if (chunk.done) break
      received += chunk.value.byteLength
      if (received > MAX_JSON_BYTES) throw new PocketAuthError('protocol', 'Pocket auth response is too large.')
      body += decoder.decode(chunk.value, { stream: true })
    }
    body += decoder.decode()
    return JSON.parse(body) as unknown
  } catch {
    await reader.cancel().catch(() => undefined)
    throw new PocketAuthError('protocol', 'Invalid or oversized Pocket auth response.')
  } finally {
    reader.releaseLock()
  }
}

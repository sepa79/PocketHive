import { scheduleDeadline } from './scheduleDeadline.js'

/**
 * Responsibility: provide a cancellable absolute polling delay.
 * Must not: issue requests, infer expiry or retain device credentials.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function waitUntil(deadline: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(signal.reason); return }
    const cancel = scheduleDeadline(deadline, () => {
      signal.removeEventListener('abort', abort)
      resolve()
    })
    const abort = () => {
      cancel()
      signal.removeEventListener('abort', abort)
      reject(signal.reason)
    }
    signal.addEventListener('abort', abort, { once: true })
  })
}

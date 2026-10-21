import { MAX_TIMER_DELAY_MS } from './sessionProtocol.js'

/**
 * Responsibility: schedule one absolute deadline without JavaScript timer overflow.
 * Must not: decide session expiry, polling outcomes or authentication state.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export function scheduleDeadline(deadline: number, action: () => void): () => void {
  let timer: ReturnType<typeof setTimeout>
  const tick = () => {
    const remaining = deadline - Date.now()
    if (remaining <= 0) action()
    else timer = setTimeout(tick, Math.min(remaining, MAX_TIMER_DELAY_MS))
  }
  timer = setTimeout(tick, Math.min(Math.max(0, deadline - Date.now()), MAX_TIMER_DELAY_MS))
  return () => clearTimeout(timer)
}

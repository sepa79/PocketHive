import type { SwarmLifecycleFeedback } from '../../lib/swarmLifecycleAction'

/**
 * Responsibility: Render swarm lifecycle controls and their pending-operation availability.
 * Must not: Decide backend admission, permissions, or terminal operation outcomes.
 * Contract: RESP-SWARM-COMMAND-ADMISSION — docs/architecture/runtime-responsibilities.md.
 */
export function SwarmLifecycleButtons({ requestPending, feedback, canRun, canManage, onStart, onStop, onRemove }: {
  requestPending: boolean
  feedback: SwarmLifecycleFeedback | undefined
  canRun: boolean
  canManage: boolean
  onStart: () => void
  onStop: () => void
  onRemove: () => void
}) {
  const pending = feedback?.status === 'pending'
  const busy = requestPending || pending
  const stopBusy = requestPending || (pending && feedback.action !== 'start')
  return <>
    <button type="button" className="actionButton" disabled={busy || !canRun} onClick={onStart}>
      <span className="actionButtonContent"><span>Start</span></span>
    </button>
    <button type="button" className="actionButton actionButtonGhost" disabled={stopBusy || !canManage} onClick={onStop}>
      <span className="actionButtonContent"><span>Stop</span></span>
    </button>
    <button type="button" className="actionButton actionButtonDanger" disabled={busy || !canManage} onClick={onRemove}>
      <span className="actionButtonContent"><span>Remove</span></span>
    </button>
  </>
}

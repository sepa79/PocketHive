import type { usePlannerSelection } from './usePlannerSelection'
import styles from './PlannerPicker.module.css'

/**
 * Responsibility: render Plan/Stream/Activity choices from the supplied Planner catalogue.
 * Must not: invent choices, fetch data or submit writes.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
export function PlannerPicker({ state, disabled = false, testPlan }: {
  state: ReturnType<typeof usePlannerSelection>; disabled?: boolean; testPlan?: string | null
}) {
  return <fieldset className={styles.picker} disabled={disabled}>
    <legend>PocketPlanner</legend>
    <div className={styles.choices}>
      <label>Plan<select className="textInput" aria-label="Plan" value={state.planId} onChange={event => state.selectPlan(event.target.value)} disabled={state.loading || !state.catalogue}>
        <option value="">Choose plan</option>
        {state.catalogue?.plans.filter(plan => !testPlan || plan.testPlan === testPlan).map(plan => <option key={plan.id} value={plan.id}>{plan.name}</option>)}
      </select></label>
      <label>Stream<select className="textInput" aria-label="Stream" value={state.streamId} onChange={event => state.selectStream(event.target.value)} disabled={!state.plan}>
        <option value="">Choose stream</option>
        {state.plan?.streams.map(stream => <option key={stream.id} value={stream.id}>{stream.name}</option>)}
      </select></label>
      <label>Activity<select className="textInput" aria-label="Activity" value={state.activityId} onChange={event => state.selectActivity(event.target.value)} disabled={!state.streamId}>
        <option value="">Choose activity</option>
        {state.plan?.activities.filter(activity => activity.streamId === state.streamId).map(activity => <option key={activity.id} value={activity.id}>{activity.name}</option>)}
      </select></label>
    </div>
    <div className={styles.note}><span>{state.loading ? 'Loading plan from PocketPlanner…' : state.error || 'Associates this run; does not mark an Activity Started or Done.'}</span>
      <button type="button" className="actionButton actionButtonGhost actionButtonTiny" onClick={state.reload} disabled={state.loading}>Reload plans</button>
    </div>
  </fieldset>
}

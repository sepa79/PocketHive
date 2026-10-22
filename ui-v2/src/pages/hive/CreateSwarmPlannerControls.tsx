import { PlannerPicker } from '../../components/planner/PlannerPicker'
import type { usePlannerCreation } from '../../components/planner/usePlannerCreation'
import styles from './CreateSwarmDialog.module.css'

/**
 * Responsibility: present optional Planner selection and retained accepted-CREATE access state.
 * Must not: interpret grants, dispatch requests or own creation/association state.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
export function CreateSwarmPlannerControls({ planner, busy }: { planner: ReturnType<typeof usePlannerCreation>; busy: boolean }) {
  return <div className={styles.planner}>
    <label><input type="checkbox" checked={planner.wantsLink} onChange={event => planner.toggle(event.target.checked)}
      disabled={busy || planner.pendingCreation !== null || !planner.canManageDeployment} />Link to PocketPlanner</label>
    {planner.wantsLink && <PlannerPicker state={planner.picker} disabled={busy || !planner.canManageDeployment} />}
    {!planner.canManageDeployment && <p className="muted">Deployment management permission is required for Planner linking.
      {planner.pendingCreation ? ' CREATE was already accepted. Restore access to retry the association, or continue in swarm details.' : ' You can create the swarm without a Planner association.'}</p>}
  </div>
}

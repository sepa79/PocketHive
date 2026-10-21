import { useEffect, useState } from 'react'
import type { PlannerRun } from './PlannerRun'
import { usePlannerSelection } from './usePlannerSelection'
import { linkPlannerRun, readCurrentPlannerRun } from './plannerApi'
import { PlannerPicker } from './PlannerPicker'

/**
 * Responsibility: show exact-run associations and explicitly append the next selected Activity.
 * Must not: start work, replace prior associations or infer progress from runtime state.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
export function PlannerRunPanel({ swarmId, runId, canManage }: { swarmId: string; runId: string; canManage: boolean }) {
  const picker = usePlannerSelection(true)
  const [loadedRun, setRun] = useState<PlannerRun | null>(null)
  const run = loadedRun?.swarmId === swarmId && loadedRun.runId === runId ? loadedRun : null
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    setRun(null); setError(null)
    readCurrentPlannerRun(swarmId, runId, controller.signal).then(value => {
      if (!controller.signal.aborted) setRun(value)
    }).catch((reason: unknown) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Cannot read run')
    })
    return () => controller.abort()
  }, [swarmId, runId])
  const plan = picker.catalogue?.plans.find(item => item.testPlan === run?.testPlan)
  const activities = plan?.activities.filter(item => run?.tags?.includes(item.tag)) ?? []
  const missing = run?.tags?.filter(tag => tag.startsWith('pp.activity:') && !activities.some(activity => activity.tag === tag)) ?? []
  return <section className="card" style={{ marginTop: 10 }} aria-label="PocketPlanner run associations">
    <h3>PocketPlanner</h3>
    {run && <div className="muted">Run {run.runId}</div>}
    <p>{run ? run.testPlan ? `Plan: ${plan?.name ?? 'Plan unavailable in the current catalogue'}` : 'This run is not linked to a plan.' : 'Loading run associations…'}</p>
    {activities.length > 0 && <ul>{activities.map(activity => <li key={activity.id}>{plan?.streams.find(stream => stream.id === activity.streamId)?.name} / {activity.name}</li>)}</ul>}
    {missing.length > 0 && <p className="warningText">{missing.length} linked Activities are no longer in the catalogue. References are retained.</p>}
    <PlannerPicker state={picker} disabled={busy || !canManage || !run} testPlan={run?.testPlan} />
    {error && <p role="alert" className="warningText">{error}</p>}
    <button type="button" className="btnPrimary" disabled={busy || !canManage || !run || !picker.selection} onClick={async () => {
      if (!picker.selection) return
      setBusy(true); setError(null)
      try { setRun(await linkPlannerRun(swarmId, runId, picker.selection)) }
      catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot link Activity') }
      finally { setBusy(false) }
    }}>{busy ? 'Linking…' : 'Link Activity'}</button>
  </section>
}

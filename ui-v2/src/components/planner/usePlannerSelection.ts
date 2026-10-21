import { useEffect, useState } from 'react'
import type { PlannerCatalogue } from './PlannerCatalogue'
import type { PlannerSelection } from './PlannerSelection'
import { fetchPlannerCatalogue } from './plannerApi'

/**
 * Responsibility: keep an explicit catalogue-backed selection and discard stale drafts on reload.
 * Must not: generate metadata references or submit run/lifecycle writes.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
export function usePlannerSelection(enabled: boolean) {
  const [catalogue, setCatalogue] = useState<PlannerCatalogue | null>(null)
  const [planId, setPlanId] = useState('')
  const [streamId, setStreamId] = useState('')
  const [activityId, setActivityId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [generation, setGeneration] = useState(0)
  useEffect(() => {
    setCatalogue(null); setPlanId(''); setStreamId(''); setActivityId(''); setError(null)
    if (!enabled) { setLoading(false); return }
    const controller = new AbortController()
    setLoading(true)
    fetchPlannerCatalogue(controller.signal).then(setCatalogue).catch((reason: unknown) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Cannot load Planner')
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [enabled, generation])
  const plan = catalogue?.plans.find(item => item.id === planId)
  const selection: PlannerSelection | null = catalogue && plan?.streams.some(item => item.id === streamId)
    && plan.activities.some(item => item.id === activityId && item.streamId === streamId)
    ? { instanceId: catalogue.instanceId, revision: catalogue.revision, planId, streamId, activityId } : null
  return {
    catalogue, plan, planId, streamId, activityId, selection, error, loading,
    selectPlan: (value: string) => { setPlanId(value); setStreamId(''); setActivityId('') },
    selectStream: (value: string) => { setStreamId(value); setActivityId('') },
    selectActivity: setActivityId, reload: () => setGeneration(value => value + 1),
  }
}

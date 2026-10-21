import type { PlannerCatalogue } from './PlannerCatalogue'
import type { PlannerSelection } from './PlannerSelection'
import type { PlannerRun } from './PlannerRun'

/**
 * Responsibility: call the authenticated PH Planner boundary without deriving planning identities.
 * Must not: persist drafts, generate tags or infer execution status.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
export async function plannerRequest(path: string, selection?: PlannerSelection, signal?: AbortSignal) {
  const response = await fetch(`/orchestrator/api/planner${path}`, {
    method: selection ? 'POST' : 'GET',
    headers: { Accept: 'application/json', ...(selection ? { 'Content-Type': 'application/json' } : {}) },
    ...(selection ? { body: JSON.stringify(selection) } : {}), signal,
  })
  if (!response.ok) {
    let message = `Planner request failed (${response.status})`
    const body = await response.text()
    try { const value = JSON.parse(body); if (typeof value.message === 'string') message = value.message } catch { message += ': response unavailable' }
    throw new Error(message)
  }
  return response
}

export async function fetchPlannerCatalogue(signal?: AbortSignal): Promise<PlannerCatalogue> {
  const value = await (await plannerRequest('/catalogue', undefined, signal)).json()
  if (value?.schemaVersion !== 1 || typeof value.instanceId !== 'string' || !Number.isSafeInteger(value.revision)
    || value.revision < 0 || !Array.isArray(value.plans) || value.plans.some((plan: PlannerCatalogue['plans'][number]) =>
      typeof plan.id !== 'string' || typeof plan.name !== 'string' || typeof plan.testPlan !== 'string'
      || !Array.isArray(plan.streams) || !Array.isArray(plan.activities))) throw new Error('Invalid PocketPlanner catalogue')
  return value
}

export async function readPlannerRun(swarmId: string, runId: string, signal?: AbortSignal): Promise<PlannerRun> {
  return (await plannerRequest(`/runs/${encodeURIComponent(swarmId)}/${encodeURIComponent(runId)}`, undefined, signal)).json()
}

export async function linkPlannerRun(swarmId: string, runId: string, selection: PlannerSelection): Promise<PlannerRun> {
  return (await plannerRequest(`/runs/${encodeURIComponent(swarmId)}/${encodeURIComponent(runId)}`, selection)).json()
}

export async function readCurrentPlannerRun(swarmId: string, expectedRunId: string, signal?: AbortSignal): Promise<PlannerRun> {
  const run = await (await plannerRequest(`/swarms/${encodeURIComponent(swarmId)}`, undefined, signal)).json()
  if (run?.swarmId !== swarmId || run?.runId !== expectedRunId) {
    throw new Error('Swarm run changed. Wait for the latest runtime snapshot before linking an Activity.')
  }
  return run
}

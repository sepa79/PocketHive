import { parseOperationState } from '@pockethive/swarm-lifecycle-contract'
import type { PlannerCreation } from './PlannerCreation'
import type { PlannerSelection } from './PlannerSelection'
import { linkPlannerRun } from './plannerApi'

/**
 * Responsibility: associate a confirmed CREATE run without repeating the accepted CREATE command.
 * Must not: infer running workload, mark Activities Started or silently select a different run.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
export async function completePlannerCreation(creation: PlannerCreation, selection: PlannerSelection, assertCanAssociate: () => void) {
  const { swarmId, accepted } = creation
  const expectedPath = `/api/swarms/${swarmId}/operations/${accepted.correlationId}`
  if (accepted.operationUrl !== expectedPath) throw new Error('Unexpected CREATE operation resource')
  const deadline = Date.now() + Math.min(accepted.timeoutMs, 120000)
  while (Date.now() < deadline) {
    assertCanAssociate()
    const response = await fetch(`/orchestrator${accepted.operationUrl}`, { headers: { Accept: 'application/json' } })
    if (!response.ok) throw new Error(`Cannot read accepted CREATE (${response.status}); do not create the swarm again`)
    const operation = await response.json()
    if (operation.swarmId !== swarmId || operation.correlationId !== accepted.correlationId
      || operation.idempotencyKey !== accepted.idempotencyKey || operation.type !== 'CREATE') throw new Error('CREATE operation identity does not match the accepted request')
    const state = parseOperationState(operation.state)
    if (state === 'SUCCEEDED') {
      if (typeof operation.runtime?.runId !== 'string' || !operation.runtime.runId) throw new Error('Confirmed CREATE has no run ID')
      assertCanAssociate()
      return linkPlannerRun(swarmId, operation.runtime.runId, selection)
    }
    if (state !== 'ACCEPTED' && state !== 'DISPATCHED') throw new Error(`CREATE ended in ${state}; no Planner association was saved`)
    await new Promise(resolve => setTimeout(resolve, 750))
  }
  throw new Error('CREATE is still unconfirmed. Retry the association; do not create the swarm again')
}

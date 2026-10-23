import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { PlannerCreation } from './PlannerCreation'
import type { PreparedPlannerSelection } from './PreparedPlannerSelection'
import { completePlannerCreation } from './completePlannerCreation'
import { plannerRequest } from './plannerApi'
import { usePlannerSelection } from './usePlannerSelection'

/**
 * Responsibility: keep optional Planner intent and the exact accepted-CREATE association handoff.
 * Must not: interpret grants, send CREATE commands or replace an accepted operation.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
export function usePlannerCreation(open: boolean, canManageDeployment: boolean) {
  const [requested, setRequested] = useState(false)
  const [pendingCreation, setPendingCreation] = useState<PlannerCreation | null>(null)
  const wantsLink = requested || pendingCreation !== null
  const current = useRef({ canManageDeployment, wantsLink, generation: 0 })
  useLayoutEffect(() => {
    const invalidated = current.current.canManageDeployment && !canManageDeployment
    current.current = { canManageDeployment, wantsLink, generation: current.current.generation + (invalidated ? 1 : 0) }
  }, [canManageDeployment, wantsLink])
  useEffect(() => { if (!canManageDeployment) setRequested(false) }, [canManageDeployment])
  const picker = usePlannerSelection(open && canManageDeployment && wantsLink)

  function assertCanAssociate(generation: number) {
    if (!current.current.canManageDeployment) {
      throw new Error('Deployment management permission is required to link a PocketPlanner Activity.')
    }
    if (generation !== current.current.generation) {
      throw new Error('Planner selection was cleared when access changed. Choose the intended Activity again before retrying.')
    }
  }

  function reset() { setPendingCreation(null); setRequested(false) }

  async function complete(creation: PlannerCreation, prepared: PreparedPlannerSelection) {
    await completePlannerCreation(creation, prepared.selection, () => assertCanAssociate(prepared.generation))
    reset()
  }

  return {
    picker, pendingCreation, wantsLink, canManageDeployment,
    retryBlocked: pendingCreation !== null && (!canManageDeployment || picker.selection === null),
    toggle: (value: boolean) => { if (canManageDeployment && pendingCreation === null) setRequested(value) },
    reset,
    remember: setPendingCreation,
    prepare: async (): Promise<PreparedPlannerSelection | null> => {
      if (!current.current.canManageDeployment || !current.current.wantsLink) return null
      const generation = current.current.generation
      const selection = picker.selection
      if (!selection) throw new Error('Choose a PocketPlanner Plan, Stream and Activity. Reload plans after a conflict.')
      try {
        await plannerRequest('/validate', selection)
      } catch (failure) {
        if (!current.current.canManageDeployment || !current.current.wantsLink || generation !== current.current.generation) return null
        throw failure
      }
      return current.current.canManageDeployment && current.current.wantsLink && generation === current.current.generation
        ? { selection, generation } : null
    },
    complete,
    retry: async () => {
      assertCanAssociate(current.current.generation)
      if (!pendingCreation) throw new Error('No accepted CREATE association is pending.')
      if (!picker.selection) throw new Error('Choose the intended Activity again before retrying the association.')
      await complete(pendingCreation, { selection: picker.selection, generation: current.current.generation })
    },
  }
}

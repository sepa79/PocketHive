import { useEffect, useMemo, useState } from 'react'
import type { AuthenticatedUser } from '../../lib/auth'
import type { SwarmStateSnapshot } from '../../lib/SwarmStateSnapshot'
import { readErrorMessage } from '../../lib/networkProxy'
import type { HiveScenario } from './HiveScenario'
import { parseHiveScenario } from './parseHiveScenario'

/**
 * Responsibility: own caller/run/template-bound Hive scenario reads and cancellation.
 * Must not: retain superseded responses or own runtime polling, capabilities or command permissions.
 * Contract: RESP-UI-SWARM-DETAIL — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-detail.
 */
export function useHiveScenario(
  swarmId: string | null,
  snapshot: SwarmStateSnapshot | null,
  caller: AuthenticatedUser | null,
  token: string | null,
) {
  const runtimeId = snapshot?.id ?? null
  const runId = snapshot?.runId ?? null
  const templateId = snapshot?.templateId?.trim() || null
  const context = useMemo(() => swarmId && runtimeId === swarmId && runId && templateId
    ? { swarmId, runId, templateId, caller, token }
    : null, [swarmId, runtimeId, runId, templateId, caller, token])
  const [stored, setStored] = useState<{
    context: NonNullable<typeof context>
    selectedScenario: HiveScenario | null
    scenarioError: string | null
  } | null>(null)

  useEffect(() => {
    if (!context) {
      setStored(null)
      return
    }
    const controller = new AbortController()
    let active = true
    void (async () => {
      try {
        const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(context.templateId)}`, {
          headers: { Accept: 'application/json' }, signal: controller.signal,
        })
        if (!response.ok) throw new Error(await readErrorMessage(response))
        const selectedScenario = parseHiveScenario(await response.json(), context.templateId)
        if (active) setStored({ context, selectedScenario, scenarioError: null })
      } catch (error) {
        if (active) setStored({
          context, selectedScenario: null,
          scenarioError: error instanceof Error ? error.message : 'Failed to load scenario',
        })
      }
    })()
    return () => {
      active = false
      controller.abort()
    }
  }, [context])

  const current = stored?.context === context ? stored : null
  return {
    selectedScenario: current?.selectedScenario ?? null,
    scenarioLoading: context !== null && current === null,
    scenarioError: current?.scenarioError ?? null,
  }
}

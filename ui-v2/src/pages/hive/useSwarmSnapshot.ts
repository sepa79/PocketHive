import { useCallback, useEffect, useRef, useState } from 'react'
import type { AuthenticatedUser } from '../../lib/auth'
import type { SwarmStateSnapshot } from '../../lib/SwarmStateSnapshot'
import { parseSwarmStateSnapshot } from '../../lib/parseSwarmStateSnapshot'
import { readErrorMessage } from '../../lib/networkProxy'

type Observation = {
  swarmId: string
  caller: AuthenticatedUser | null
  token: string | null
  snapshot: SwarmStateSnapshot | null
  loading: boolean
  error: string | null
}

/**
 * Responsibility: own caller/selection-bound Hive detail reads and polling through official ingress.
 * Must not: publish superseded responses, synthesize runtime state or decide command permissions.
 * Contract: RESP-UI-SWARM-DETAIL — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-detail.
 */
export function useSwarmSnapshot(swarmId: string | null, caller: AuthenticatedUser | null, token: string | null) {
  const [stored, setStored] = useState<Observation | null>(null)
  const generation = useRef(0)
  const pending = useRef<{ controller: AbortController; promise: Promise<void> } | null>(null)
  const selection = useRef<Pick<Observation, 'swarmId' | 'caller' | 'token'> | null>(null)
  const cancel = useCallback(() => {
    selection.current = null
    generation.current++
    pending.current?.controller.abort()
    pending.current = null
  }, [])

  const reload = useCallback(async () => {
    const selected = selection.current
    if (!swarmId || selected?.swarmId !== swarmId || selected.caller !== caller || selected.token !== token) return
    if (pending.current) return pending.current.promise
    const request = ++generation.current
    const controller = new AbortController()
    const owner = { swarmId, caller, token }
    setStored(previous => ({
      ...owner,
      snapshot: previous?.swarmId === swarmId && previous.caller === caller && previous.token === token ? previous.snapshot : null,
      loading: true,
      error: null,
    }))
    const promise = (async () => {
      try {
        const response = await fetch(`/orchestrator/api/swarms/${encodeURIComponent(swarmId)}`, {
          headers: { Accept: 'application/json' }, signal: controller.signal,
        })
        if (response.status === 404) throw new Error(`Swarm '${swarmId}' was not found.`)
        if (!response.ok) throw new Error(await readErrorMessage(response))
        const snapshot = parseSwarmStateSnapshot(await response.json(), swarmId)
        if (request === generation.current) setStored({ ...owner, snapshot, loading: false, error: null })
      } catch (error) {
        if (request === generation.current) setStored({
          ...owner, snapshot: null, loading: false,
          error: error instanceof Error ? error.message : 'Failed to load swarm state',
        })
      } finally {
        if (request === generation.current) pending.current = null
      }
    })()
    pending.current = { controller, promise }
    return promise
  }, [swarmId, caller, token])

  useEffect(() => {
    selection.current = swarmId === null ? null : { swarmId, caller, token }
    void reload()
    const interval = swarmId ? window.setInterval(() => void reload(), 5000) : null
    return () => {
      cancel()
      if (interval !== null) window.clearInterval(interval)
    }
  }, [reload, swarmId, caller, token, cancel])

  const current = stored?.swarmId === swarmId && stored.caller === caller && stored.token === token ? stored : null
  return {
    snapshot: current?.snapshot ?? null,
    error: current?.error ?? null,
    loading: swarmId !== null && (current?.loading ?? true),
    reload,
  }
}

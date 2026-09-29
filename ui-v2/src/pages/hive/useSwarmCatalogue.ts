import { useCallback, useEffect, useRef, useState } from 'react'
import { loadSwarmAccess } from '../../lib/swarmAccessApi'
import { readErrorMessage } from '../../lib/networkProxy'
import type { SwarmAccess } from '../../lib/SwarmAccess'
import type { SwarmSummary } from '../../lib/SwarmSummary'
import type { AuthenticatedUser } from '../../lib/auth'

/**
 * Responsibility: load swarm catalogue and caller permissions for Hive presentation.
 * Must not: derive permissions from grants or allow stale requests to replace newer observations.
 * Contract: RESP-SWARM-ACCESS-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-swarm-access-projection.
 */
export function useSwarmCatalogue(caller: AuthenticatedUser | null) {
  const [swarms, setSwarms] = useState<SwarmSummary[]>([])
  const [swarmAccess, setSwarmAccess] = useState<ReadonlyMap<string, SwarmAccess>>(new Map())
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [accessOwner, setAccessOwner] = useState<AuthenticatedUser | null | undefined>(undefined)
  const swarmLoadVersion = useRef(0)
  const inFlight = useRef<Promise<void> | null>(null)
  useEffect(() => () => {
    swarmLoadVersion.current++
    inFlight.current = null
  }, [caller])
  const reload = useCallback(async (options?: { showLoading?: boolean }) => {
    const showLoading = options?.showLoading ?? true
    if (!showLoading && inFlight.current) return inFlight.current
    const version = ++swarmLoadVersion.current
    setSwarmAccess(new Map())
    if (showLoading) setLoading(true)
    const request = (async () => {
      try {
        const [response, access] = await Promise.all([
          fetch('/orchestrator/api/swarms', { headers: { Accept: 'application/json' } }),
          loadSwarmAccess(),
        ])
        if (!response.ok) throw new Error(await readErrorMessage(response))
        const payload = (await response.json()) as SwarmSummary[]
        if (version !== swarmLoadVersion.current) return
        setError(null)
        setSwarms(Array.isArray(payload) ? payload : [])
        setSwarmAccess(access)
        setAccessOwner(caller)
      } catch (err) {
        if (version !== swarmLoadVersion.current) return
        setSwarmAccess(new Map())
        setError(err instanceof Error ? err.message : 'Failed to load swarms')
      } finally {
        if (version === swarmLoadVersion.current) {
          setLoading(false)
          inFlight.current = null
        }
      }
    })()
    inFlight.current = request
    return request
  }, [caller])

  return { swarms, access: accessOwner === caller ? swarmAccess : new Map<string, SwarmAccess>(), loading, error, reload }
}

import { useCallback, useEffect, useRef, useState } from 'react'
import type { AccessObservation } from './AccessObservation'

/**
 * Responsibility: own caller-bound asynchronous permission observations and explicit retries.
 * Must not: interpret grants, retain decisions across callers or change login state.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export function useAccessObservation<T>(caller: object | null, token: string | null,
  load: (token: string) => Promise<T>, enabled = true) {
  const [stored, setStored] = useState<{
    caller: object | null; token: string | null; observation: AccessObservation<T>
  } | null>(null)
  const generation = useRef(0)
  const active = enabled && caller !== null && token !== null
  const reload = useCallback(async () => {
    if (!active || token === null) return
    const request = ++generation.current
    setStored({ caller, token, observation: { status: 'loading', value: null, error: null } })
    try {
      const value = await load(token)
      if (request === generation.current) setStored({ caller, token, observation: { status: 'ready', value, error: null } })
    } catch (error) {
      if (request === generation.current) setStored({ caller, token, observation: {
        status: 'error', value: null, error: error instanceof Error ? error.message : 'Failed to load permissions',
      } })
    }
  }, [active, caller, token, load])
  useEffect(() => {
    if (active) void reload()
    else setStored(null)
    return () => { generation.current++ }
  }, [active, reload])
  const observation: AccessObservation<T> = !active
    ? { status: 'idle', value: null, error: null }
    : stored?.caller === caller && stored.token === token
      ? stored.observation
      : { status: 'loading', value: null, error: null }
  return { ...observation, reload }
}

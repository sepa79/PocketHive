import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { listSwarms } from '../lib/orchestratorApi'
import type { SwarmSummary } from '../types/orchestrator'
import { setSwarmMetadataRefreshHandler } from '../lib/stompClient'

export interface SwarmMetadataContextValue {
  swarms: SwarmSummary[]
  ensureSwarms: () => Promise<SwarmSummary[]>
  refreshSwarms: () => Promise<SwarmSummary[]>
  getBeeImage: (
    swarmId: string | null | undefined,
    role: string | null | undefined,
  ) => string | null
  getControllerImage: (swarmId: string | null | undefined) => string | null
  findSwarm: (swarmId: string | null | undefined) => SwarmSummary | null
}

const defaultValue: SwarmMetadataContextValue = {
  swarms: [],
  ensureSwarms: async () => [],
  refreshSwarms: async () => [],
  getBeeImage: () => null,
  getControllerImage: () => null,
  findSwarm: () => null,
}

export const SwarmMetadataContext = createContext<SwarmMetadataContextValue>(defaultValue)

export function useSwarmMetadata(): SwarmMetadataContextValue {
  return useContext(SwarmMetadataContext)
}

interface Props {
  children: ReactNode
}

function normalizeSwarmId(value: string | null | undefined): string | null {
  if (value == null) return 'default'
  const trimmed = value.trim()
  if (!trimmed) return 'default'
  return trimmed
}

export function SwarmMetadataProvider({ children }: Props) {
  const [swarms, setSwarms] = useState<SwarmSummary[]>([])
  const [hasLoaded, setHasLoaded] = useState(false)
  const inFlightRef = useRef<Promise<SwarmSummary[]> | null>(null)
  const hasLoadedRef = useRef(false)

  useEffect(() => {
    hasLoadedRef.current = hasLoaded
  }, [hasLoaded])

  const runFetch = useCallback(() => {
    if (inFlightRef.current) {
      return inFlightRef.current
    }

    const fetchTask = (async () => {
      const payload = await listSwarms()
      setSwarms(payload)
      setHasLoaded(true)
      return payload
    })()

    const wrapped = fetchTask
      .catch((error) => {
        if (!hasLoadedRef.current) {
          setSwarms([])
        }
        console.error('Failed to load swarm metadata', error)
        return [] as SwarmSummary[]
      })
      .finally(() => {
        if (inFlightRef.current === wrapped) {
          inFlightRef.current = null
        }
      })

    inFlightRef.current = wrapped
    return wrapped
  }, [])

  const ensureSwarms = useCallback(() => {
    if (hasLoaded) {
      return inFlightRef.current ?? Promise.resolve(swarms)
    }
    return runFetch()
  }, [hasLoaded, swarms, runFetch])

  const refreshSwarms = useCallback(() => {
    return runFetch()
  }, [runFetch])

  const swarmIndex = useMemo(() => {
    const index = new Map<string, SwarmSummary>()
    swarms.forEach((swarm) => {
      const key = normalizeSwarmId(swarm.id)
      if (key) {
        index.set(key, swarm)
      }
    })
    return index
  }, [swarms])

  const beeIndex = useMemo(() => {
    const index = new Map<string, Map<string, string | null>>()
    swarms.forEach((swarm) => {
      const swarmKey = normalizeSwarmId(swarm.id)
      if (!swarmKey) return
      const roleIndex = new Map<string, string | null>()
      swarm.bees.forEach((bee) => {
        if (!bee) return
        const normalizedRole = bee.role?.trim().toLowerCase()
        if (!normalizedRole) return
        roleIndex.set(normalizedRole, bee.image ?? null)
      })
      if (roleIndex.size > 0) {
        index.set(swarmKey, roleIndex)
      }
    })
    return index
  }, [swarms])

  const getBeeImage = useCallback(
    (swarmId: string | null | undefined, role: string | null | undefined) => {
      const normalizedRole = role?.trim().toLowerCase()
      if (!normalizedRole) return null
      const swarmKey = normalizeSwarmId(swarmId)
      if (!swarmKey) return null
      const roleIndex = beeIndex.get(swarmKey)
      if (!roleIndex) return null
      return roleIndex.get(normalizedRole) ?? null
    },
    [beeIndex],
  )

  const getControllerImage = useCallback(
    (swarmId: string | null | undefined) => {
      const swarmKey = normalizeSwarmId(swarmId)
      if (!swarmKey) return null
      const swarm = swarmIndex.get(swarmKey)
      return swarm?.controllerImage ?? null
    },
    [swarmIndex],
  )

  const findSwarm = useCallback(
    (swarmId: string | null | undefined) => {
      const swarmKey = normalizeSwarmId(swarmId)
      if (!swarmKey) return null
      return swarmIndex.get(swarmKey) ?? null
    },
    [swarmIndex],
  )

  useEffect(() => {
    setSwarmMetadataRefreshHandler((swarmId) => {
      const normalized = normalizeSwarmId(swarmId)
      if (normalized) {
        setSwarms((current) => current.filter((swarm) => normalizeSwarmId(swarm.id) !== normalized))
      }
      void refreshSwarms()
    })
    return () => setSwarmMetadataRefreshHandler(null)
  }, [refreshSwarms])

  const value = useMemo<SwarmMetadataContextValue>(
    () => ({
      swarms,
      ensureSwarms,
      refreshSwarms,
      getBeeImage,
      getControllerImage,
      findSwarm,
    }),
    [swarms, ensureSwarms, refreshSwarms, getBeeImage, getControllerImage, findSwarm],
  )

  return <SwarmMetadataContext.Provider value={value}>{children}</SwarmMetadataContext.Provider>
}

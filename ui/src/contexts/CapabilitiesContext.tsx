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
import { apiFetch } from '../lib/api'
import {
  buildManifestIndex,
  normalizeManifests,
  type ManifestIndex,
} from '../lib/capabilities'
import type { CapabilityManifest } from '../types/capabilities'

export interface CapabilitiesContextValue {
  manifests: CapabilityManifest[]
  manifestIndex: ManifestIndex
  ensureCapabilities: () => Promise<CapabilityManifest[]>
  refreshCapabilities: () => Promise<CapabilityManifest[]>
  getManifestForRole: (role: string | null | undefined) => CapabilityManifest | null
}

const defaultManifestIndex = buildManifestIndex([])

const defaultValue: CapabilitiesContextValue = {
  manifests: [],
  manifestIndex: defaultManifestIndex,
  ensureCapabilities: async () => [],
  refreshCapabilities: async () => [],
  getManifestForRole: () => null,
}

export const CapabilitiesContext = createContext<CapabilitiesContextValue>(defaultValue)

export function useCapabilities(): CapabilitiesContextValue {
  return useContext(CapabilitiesContext)
}

interface Props {
  children: ReactNode
}

export function CapabilitiesProvider({ children }: Props) {
  const [manifests, setManifests] = useState<CapabilityManifest[]>([])
  const [hasLoaded, setHasLoaded] = useState(false)
  const hasLoadedRef = useRef(false)
  const inFlightRef = useRef<Promise<CapabilityManifest[]> | null>(null)

  useEffect(() => {
    hasLoadedRef.current = hasLoaded
  }, [hasLoaded])

  const runFetch = useCallback(() => {
    if (inFlightRef.current) {
      return inFlightRef.current
    }

    const fetchTask = (async () => {
      const response = await apiFetch('/scenario-manager/api/capabilities?all=true', {
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) {
        throw new Error('Failed to load capabilities')
      }
      const payload = await response.json()
      const normalized = normalizeManifests(payload)
      setManifests(normalized)
      setHasLoaded(true)
      return normalized
    })()

    const wrapped = fetchTask
      .catch((error) => {
        if (!hasLoadedRef.current) {
          setManifests([])
        }
        console.error('Failed to load capabilities', error)
        return [] as CapabilityManifest[]
      })
      .finally(() => {
        if (inFlightRef.current === wrapped) {
          inFlightRef.current = null
        }
      })

    inFlightRef.current = wrapped
    return wrapped
  }, [])

  const ensureCapabilities = useCallback(() => {
    if (hasLoaded) {
      return inFlightRef.current ?? Promise.resolve(manifests)
    }
    return runFetch()
  }, [hasLoaded, manifests, runFetch])

  const refreshCapabilities = useCallback(() => {
    return runFetch()
  }, [runFetch])

  const manifestIndex = useMemo(() => buildManifestIndex(manifests), [manifests])

  const roleManifestMap = useMemo(() => {
    const map = new Map<string, CapabilityManifest>()
    manifests.forEach((manifest) => {
      const key = typeof manifest.role === 'string' ? manifest.role.trim().toLowerCase() : ''
      if (!key || map.has(key)) return
      map.set(key, manifest)
    })
    return map
  }, [manifests])

  const getManifestForRole = useCallback(
    (role: string | null | undefined) => {
      if (!role) return null
      const normalized = role.trim().toLowerCase()
      if (!normalized) return null
      return roleManifestMap.get(normalized) ?? null
    },
    [roleManifestMap],
  )

  const value = useMemo<CapabilitiesContextValue>(
    () => ({
      manifests,
      manifestIndex,
      ensureCapabilities,
      refreshCapabilities,
      getManifestForRole,
    }),
    [manifests, manifestIndex, ensureCapabilities, refreshCapabilities, getManifestForRole],
  )

  return <CapabilitiesContext.Provider value={value}>{children}</CapabilitiesContext.Provider>
}


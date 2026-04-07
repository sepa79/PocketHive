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
  resolveManifestForImage,
  type ManifestResolution,
  type ManifestIndex,
} from '../lib/capabilities'
import type { CapabilityManifest } from '../types/capabilities'

export interface CapabilitiesContextValue {
  manifests: CapabilityManifest[]
  manifestIndex: ManifestIndex
  capabilityFallbackTag: string | null
  ensureCapabilities: () => Promise<CapabilityManifest[]>
  refreshCapabilities: () => Promise<CapabilityManifest[]>
  getManifestForImage: (image: string | null | undefined) => CapabilityManifest | null
  resolveManifestForImage: (image: string | null | undefined) => ManifestResolution
}

const defaultManifestIndex = buildManifestIndex([])

const defaultValue: CapabilitiesContextValue = {
  manifests: [],
  manifestIndex: defaultManifestIndex,
  capabilityFallbackTag: null,
  ensureCapabilities: async () => [],
  refreshCapabilities: async () => [],
  getManifestForImage: () => null,
  resolveManifestForImage: () => ({
    manifest: null,
    kind: 'none',
    requestedTag: null,
    resolvedTag: null,
  }),
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
  const [capabilityFallbackTag, setCapabilityFallbackTag] = useState<string | null>(null)
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
      const fallbackTagHeader = response.headers.get('X-Pockethive-Capability-Fallback-Tag')
      setCapabilityFallbackTag(
        typeof fallbackTagHeader === 'string' && fallbackTagHeader.trim().length > 0
          ? fallbackTagHeader.trim()
          : null,
      )
      setManifests(normalized)
      setHasLoaded(true)
      return normalized
    })()

    const wrapped = fetchTask
      .catch((error) => {
        if (!hasLoadedRef.current) {
          setManifests([])
          setCapabilityFallbackTag(null)
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

  const getManifestForImage = useCallback(
    (image: string | null | undefined) => {
      if (!image) return null
      return resolveManifestForImage(image, manifestIndex, capabilityFallbackTag).manifest
    },
    [capabilityFallbackTag, manifestIndex],
  )

  const resolveManifestForImageFromContext = useCallback(
    (image: string | null | undefined) => {
      if (!image) {
        return {
          manifest: null,
          kind: 'none',
          requestedTag: null,
          resolvedTag: null,
        } satisfies ManifestResolution
      }
      return resolveManifestForImage(image, manifestIndex, capabilityFallbackTag)
    },
    [capabilityFallbackTag, manifestIndex],
  )

  const value = useMemo<CapabilitiesContextValue>(
    () => ({
      manifests,
      manifestIndex,
      capabilityFallbackTag,
      ensureCapabilities,
      refreshCapabilities,
      getManifestForImage,
      resolveManifestForImage: resolveManifestForImageFromContext,
    }),
    [
      manifests,
      manifestIndex,
      capabilityFallbackTag,
      ensureCapabilities,
      refreshCapabilities,
      getManifestForImage,
      resolveManifestForImageFromContext,
    ],
  )

  return <CapabilitiesContext.Provider value={value}>{children}</CapabilitiesContext.Provider>
}

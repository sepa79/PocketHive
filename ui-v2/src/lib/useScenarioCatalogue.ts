import { useCallback, useEffect, useRef, useState } from 'react'
import { listBundleTemplates, listBundleWorkspaces, type BundleTemplateEntry } from './scenariosApi'
import { loadBundleAccess } from './bundleAccessApi'
import type { AuthenticatedUser } from './auth'

const EMPTY_ENTRIES: BundleTemplateEntry[] = []
const NO_EDIT_ACCESS: ReadonlyMap<string, boolean> = new Map()

/**
 * Responsibility: load caller-specific scenario catalogue and edit projections for UI.
 * Must not: interpret grant scopes, authorize mutations, or cache across callers/modal closures.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access.
 */
export function useScenarioCatalogue(mode: 'read' | 'run', caller: AuthenticatedUser | null, active = true) {
  const [observation, setObservation] = useState<{
    caller: AuthenticatedUser | null
    entries: BundleTemplateEntry[]
    access: ReadonlyMap<string, boolean>
  } | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const version = useRef(0)
  const reload = useCallback(async (): Promise<BundleTemplateEntry[]> => {
    if (!active) return []
    const request = ++version.current
    setLoading(true)
    setError(null)
    try {
      const [entries, access] = mode === 'read'
        ? await Promise.all([listBundleWorkspaces(), loadBundleAccess()])
        : [await listBundleTemplates(), NO_EDIT_ACCESS] as const
      if (request !== version.current) return []
      setObservation({ caller, entries, access })
      return entries
    } catch (failure) {
      if (request === version.current) setError(failure instanceof Error ? failure.message : 'Failed to load scenarios')
      return []
    } finally {
      if (request === version.current) setLoading(false)
    }
  }, [active, caller, mode])
  useEffect(() => {
    if (active) void reload()
    else setObservation(null)
    return () => { version.current++ }
  }, [active, reload])
  const current = active && observation?.caller === caller ? observation : null
  return {
    entries: current?.entries ?? EMPTY_ENTRIES,
    access: loading || error ? NO_EDIT_ACCESS : current?.access ?? NO_EDIT_ACCESS,
    loading: active && (loading || (!current && !error)),
    error,
    reload,
  }
}

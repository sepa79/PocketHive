import type { NetworkBinding } from '../../lib/NetworkBinding'

/**
 * Responsibility: display the observed binding mode or its explicit availability state.
 * Must not: infer effective mode from desired swarm configuration or defaults.
 * Contract: RESP-UI-NETWORK-BINDING-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-ui-network-binding-projection.
 */
export default function NetworkBindingModeBadge({ binding, loading, error }: {
  binding: NetworkBinding | null
  loading: boolean
  error: string | null
}) {
  if (loading) return <span className="pill">Loading…</span>
  if (error) return <span className="pill pillWarn">Unavailable</span>
  if (!binding) return <span className="pill">No binding</span>
  return <span className={binding.effectiveMode === 'PROXIED' ? 'pill pillInfo' : 'pill pillWarn'}>
    {binding.effectiveMode}
  </span>
}

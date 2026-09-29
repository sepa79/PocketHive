import type { NetworkMode } from './NetworkMode'
import type { NetworkBinding } from './NetworkBinding'
import type { ResolvedSutEndpoint } from './ResolvedSutEndpoint'

/**
 * Responsibility: decode the browser projection of network binding responses.
 * Must not: decide routing or supply a mode absent from the producer response.
 * Contract: RESP-UI-NETWORK-BINDING-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-ui-network-binding-projection.
 */
function parseMode(value: unknown, field: string): NetworkMode {
  if (value === 'DIRECT' || value === 'PROXIED') return value
  throw new Error(`Invalid network binding: ${field} must be DIRECT or PROXIED`)
}

function normalizeEndpoint(entry: unknown): ResolvedSutEndpoint | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  const endpointId = typeof value.endpointId === 'string' ? value.endpointId.trim() : ''
  if (!endpointId) return null
  return {
    endpointId,
    kind: typeof value.kind === 'string' && value.kind.trim().length > 0 ? value.kind.trim() : null,
    clientBaseUrl: typeof value.clientBaseUrl === 'string' && value.clientBaseUrl.trim().length > 0 ? value.clientBaseUrl.trim() : null,
    clientAuthority:
      typeof value.clientAuthority === 'string' && value.clientAuthority.trim().length > 0 ? value.clientAuthority.trim() : null,
    upstreamAuthority:
      typeof value.upstreamAuthority === 'string' && value.upstreamAuthority.trim().length > 0
        ? value.upstreamAuthority.trim()
        : null,
  }
}

export function parseNetworkBindings(data: unknown): NetworkBinding[] {
  if (!Array.isArray(data)) throw new Error('Invalid network bindings response: expected an array')
  return data
    .map((entry) => {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
        throw new Error('Invalid network binding entry')
      }
      const value = entry as Record<string, unknown>
      const swarmId = typeof value.swarmId === 'string' ? value.swarmId.trim() : ''
      const sutId = typeof value.sutId === 'string' ? value.sutId.trim() : ''
      if (!swarmId || !sutId) throw new Error('Invalid network binding: missing swarmId or sutId')
      return {
        swarmId,
        sutId,
        networkMode: parseMode(value.networkMode, 'networkMode'),
        networkProfileId:
          typeof value.networkProfileId === 'string' && value.networkProfileId.trim().length > 0
            ? value.networkProfileId.trim()
            : null,
        effectiveMode: parseMode(value.effectiveMode, 'effectiveMode'),
        requestedBy: typeof value.requestedBy === 'string' ? value.requestedBy.trim() : 'unknown',
        appliedAt: typeof value.appliedAt === 'string' && value.appliedAt.trim().length > 0 ? value.appliedAt.trim() : null,
        affectedEndpoints: Array.isArray(value.affectedEndpoints)
          ? value.affectedEndpoints.map(normalizeEndpoint).filter((item): item is ResolvedSutEndpoint => item !== null)
          : [],
      } satisfies NetworkBinding
    })
}


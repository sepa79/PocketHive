import type { SwarmAccess } from './SwarmAccess'

/**
 * Responsibility: load the caller-specific swarm access projection through ingress.
 * Must not: interpret grants, resolve scenario scope or authorize commands locally.
 * Contract: RESP-SWARM-ACCESS-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-swarm-access-projection.
 */
export async function loadSwarmAccess(): Promise<ReadonlyMap<string, SwarmAccess>> {
  const response = await fetch('/orchestrator/api/access/swarms', {
    headers: { Accept: 'application/json' }, cache: 'no-store',
  })
  if (!response.ok) throw new Error(`Failed to load swarm permissions: HTTP ${response.status}`)
  const payload: unknown = await response.json()
  if (!payload || typeof payload !== 'object' || !('swarms' in payload) || !Array.isArray(payload.swarms)) {
    throw new Error('Invalid swarm permissions response')
  }
  const result = new Map<string, SwarmAccess>()
  for (const entry of payload.swarms) {
    if (!entry || typeof entry !== 'object' || typeof entry.swarmId !== 'string' || !entry.swarmId.trim() ||
        typeof entry.canRun !== 'boolean' || typeof entry.canManage !== 'boolean' || result.has(entry.swarmId)) {
      throw new Error('Invalid swarm permissions entry')
    }
    result.set(entry.swarmId, { swarmId: entry.swarmId, canRun: entry.canRun, canManage: entry.canManage })
  }
  return result
}

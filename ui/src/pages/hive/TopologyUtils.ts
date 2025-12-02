// Small shared helpers for topology code (IDs, swarm grouping, etc.).

// Swarm ids that represent "outside" or global traffic rather than a concrete swarm.
export const OUTSIDE_SWARMS = new Set(['hive'])

export function normalizeSwarmId(id?: string): string | undefined {
  if (!id) return undefined
  const trimmed = id.trim()
  if (!trimmed || OUTSIDE_SWARMS.has(trimmed)) return undefined
  return trimmed
}

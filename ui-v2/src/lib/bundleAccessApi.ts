/**
 * Responsibility: load caller-specific bundle edit permissions from Scenario Manager.
 * Must not: infer permissions from grants or bundle paths.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access.
 */
export async function loadBundleAccess(): Promise<ReadonlyMap<string, boolean>> {
  const response = await fetch('/scenario-manager/api/access/bundles', {
    headers: { Accept: 'application/json' }, cache: 'no-store',
  })
  if (!response.ok) throw new Error(`Failed to load bundle permissions: HTTP ${response.status}`)
  const payload: unknown = await response.json()
  if (!payload || typeof payload !== 'object' || !('bundles' in payload) || !Array.isArray(payload.bundles)) {
    throw new Error('Invalid bundle permissions response')
  }
  const result = new Map<string, boolean>()
  for (const entry of payload.bundles) {
    if (!entry || typeof entry !== 'object' || typeof entry.bundleKey !== 'string' || !entry.bundleKey.trim() ||
        typeof entry.canManage !== 'boolean' || result.has(entry.bundleKey)) {
      throw new Error('Invalid bundle permissions entry')
    }
    result.set(entry.bundleKey, entry.canManage)
  }
  return result
}

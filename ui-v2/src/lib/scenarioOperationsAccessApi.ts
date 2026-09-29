import type { ScenarioOperationsAccessView } from './ScenarioOperationsAccessView'

/**
 * Responsibility: load and validate ScenarioOperationsAccessView through public ingress.
 * Must not: interpret grants or replace failed observations with permissions.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export async function scenarioOperationsAccessApi(token: string): Promise<ScenarioOperationsAccessView> {
  const response = await fetch('/scenario-manager/api/access/scenarios', {
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}` }, cache: 'no-store',
  })
  if (!response.ok) throw new Error(`Failed to load permissions: HTTP ${response.status}`)
  const value: unknown = await response.json()
  if (!value || typeof value !== 'object' ||
      !('canReload' in value) || typeof value.canReload !== 'boolean' ||
      !('canUpload' in value) || typeof value.canUpload !== 'boolean') {
    throw new Error('Invalid permissions response')
  }
  return { canReload: value.canReload, canUpload: value.canUpload }
}

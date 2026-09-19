/**
 * Responsibility: load the read-only Rabbit subscription projection through Orchestrator ingress.
 * Must not: construct destinations or supply local broker-name defaults.
 * Contract: docs/ORCHESTRATOR-REST.md#control-plane-connection-information.
 */
export type ControlPlaneConnectionInfo = {
  subscriptionDestination: string
  destinationPrefix: string
}

export async function loadControlPlaneConnectionInfo(): Promise<ControlPlaneConnectionInfo> {
  const response = await fetch('/orchestrator/api/control-plane/info')
  if (!response.ok) throw new Error(`Control-plane connection information: HTTP ${response.status}`)
  const value: unknown = await response.json()
  if (!value || typeof value !== 'object' ||
      !('subscriptionDestination' in value) || typeof value.subscriptionDestination !== 'string' ||
      !value.subscriptionDestination.trim() ||
      !('destinationPrefix' in value) || typeof value.destinationPrefix !== 'string' ||
      !value.destinationPrefix.trim()) {
    throw new Error('Invalid control-plane connection information')
  }
  return { subscriptionDestination: value.subscriptionDestination, destinationPrefix: value.destinationPrefix }
}

import type { NetworkMode } from './NetworkMode'
import type { ResolvedSutEndpoint } from './ResolvedSutEndpoint'

export type NetworkBinding = {
  swarmId: string
  sutId: string
  networkMode: NetworkMode
  networkProfileId: string | null
  effectiveMode: NetworkMode
  requestedBy: string
  appliedAt: string | null
  affectedEndpoints: ResolvedSutEndpoint[]
}

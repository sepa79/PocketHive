import type { SwarmStateView } from '@pockethive/swarm-lifecycle-contract'
import type { SwarmRuntimeWorker } from './SwarmRuntimeWorker'

export type SwarmStateSnapshot = Pick<SwarmStateView,
  'id' | 'runId' | 'templateId' | 'controllerImage' | 'controllerState' |
  'workloadState' | 'health' | 'observedAt' | 'observationStale'> & {
  workers: SwarmRuntimeWorker[]
}

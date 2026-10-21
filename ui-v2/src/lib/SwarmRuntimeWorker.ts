import type { WorkerObservation, IoState } from '@pockethive/swarm-lifecycle-contract'

export type SwarmRuntimeWorker = Pick<WorkerObservation,
  'role' | 'instance' | 'enabled' | 'tps' | 'stale' | 'lastSeenAt'> & {
  workIo: Pick<IoState, 'input' | 'output'> | null
  runtime: { image: string | null } | null
  config: Record<string, unknown> | null
}

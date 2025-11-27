export type HealthStatus = 'OK' | 'WARN' | 'ALERT'

export interface QueueInfo {
  name: string
  role: 'producer' | 'consumer'
  depth?: number
  consumers?: number
  oldestAgeSec?: number
}

export interface Component {
  id: string
  name: string
  role: string
  swarmId?: string
  version?: string
  image?: string | null
  startedAt?: number
  lastHeartbeat: number
  status?: string
  queues: QueueInfo[]
  config?: Record<string, unknown>
}

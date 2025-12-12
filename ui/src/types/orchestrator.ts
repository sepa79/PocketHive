export interface BeeSummary {
  role: string
  image: string | null
}


export interface SwarmSummary {
  id: string
  status: string
  health: string | null
  heartbeat: string | null
  workEnabled: boolean
  controllerEnabled: boolean
  templateId: string | null
  controllerImage: string | null
  sutId?: string | null
  stackName: string | null
  bees: BeeSummary[]
}

export interface SwarmJournalEntry {
  timestamp: string
  swarmId: string
  severity: string
  direction: 'IN' | 'OUT' | 'LOCAL'
  kind: string
  type: string
  origin: string
  scope: {
    swarmId: string
    role: string | null
    instance: string | null
  }
  correlationId: string | null
  idempotencyKey: string | null
  routingKey: string | null
  data: Record<string, unknown> | null
  raw: Record<string, unknown> | null
  extra: Record<string, unknown> | null
}

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
  actor: string
  kind: string
  severity: string
  correlationId: string | null
  idempotencyKey: string | null
  message: string | null
  details?: Record<string, unknown> | null
}

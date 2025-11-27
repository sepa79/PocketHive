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
  stackName: string | null
  bees: BeeSummary[]
}

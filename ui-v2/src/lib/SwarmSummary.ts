import type { NetworkMode } from './NetworkMode'

export type SwarmSummary = {
  id: string
  runtimeIntent: string
  workloadIntent: string
  controllerState: string
  workloadState: string
  health?: string | null
  runtimeResourceState: string
  templateId?: string | null
  controllerImage?: string | null
  bees?: { role: string; image: string | null }[]
  sutId?: string | null
  networkMode?: NetworkMode
  networkProfileId?: string | null
}


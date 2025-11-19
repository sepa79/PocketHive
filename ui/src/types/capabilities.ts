export interface CapabilityImage {
  name?: string | null
  tag?: string | null
  digest?: string | null
}

export interface CapabilityConfigEntry {
  name: string
  type: string
  default?: unknown
  min?: number
  max?: number
  options?: unknown
  multiline?: boolean
  ui?: unknown
}

export type CapabilityShape =
  | 'circle'
  | 'square'
  | 'triangle'
  | 'diamond'
  | 'pentagon'
  | 'hexagon'
  | 'star'

export interface CapabilityUi {
  label?: string
  color?: string
  abbreviation?: string
  shape?: CapabilityShape
  [key: string]: unknown
}

export interface CapabilityActionParameter {
  name: string
  type: string
  default?: unknown
  required?: boolean
  ui?: unknown
}

export interface CapabilityAction {
  id: string
  label: string
  params: CapabilityActionParameter[]
}

export interface CapabilityPanel {
  id: string
  options?: unknown
}

export interface CapabilityManifest {
  schemaVersion: string
  capabilitiesVersion: string
  image: CapabilityImage
  role: string
  config: CapabilityConfigEntry[]
  actions: CapabilityAction[]
  panels: CapabilityPanel[]
  ui?: CapabilityUi
}

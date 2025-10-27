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
  multiline?: boolean
  ui?: unknown
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
}

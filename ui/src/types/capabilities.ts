export type CapabilityOptionValue = string | number | boolean

export interface CapabilityOption {
  value: CapabilityOptionValue
  label?: string
  description?: string
}

export interface CapabilityValidationRules {
  minLength?: number
  maxLength?: number
  pattern?: string
  format?: string
  minimum?: number
  maximum?: number
  exclusiveMinimum?: number
  exclusiveMaximum?: number
  minItems?: number
  maxItems?: number
  enum?: unknown[]
}

export interface CapabilityUIHints {
  component?: string
  placeholder?: string
  multiline?: boolean
  monospace?: boolean
  secret?: boolean
  group?: string
  order?: number
}

export type CapabilityFieldType =
  | 'string'
  | 'text'
  | 'integer'
  | 'number'
  | 'boolean'
  | 'enum'
  | 'select'
  | 'multiselect'
  | 'object'
  | 'array'
  | 'duration'
  | 'cron'
  | 'json'

export interface CapabilityField {
  name: string
  label?: string
  description?: string
  type: CapabilityFieldType
  default?: unknown
  required?: boolean
  mutable?: boolean
  sensitive?: boolean
  repeats?: boolean
  options?: CapabilityOption[]
  validation?: CapabilityValidationRules
  ui?: CapabilityUIHints
}

export interface CapabilityActionParameter {
  name: string
  label?: string
  description?: string
  type: 'string' | 'text' | 'integer' | 'number' | 'boolean' | 'enum' | 'json'
  required?: boolean
  validation?: CapabilityValidationRules
}

export interface CapabilityAction {
  id: string
  label: string
  description?: string
  method: string
  endpoint: string
  params?: CapabilityActionParameter[]
  timeoutSeconds?: number
}

export interface CapabilityPanel {
  type: string
  label?: string
  description?: string
  config?: CapabilityField[]
  source?: string
  refreshIntervalSeconds?: number
}

export interface CapabilityManifest {
  schemaVersion?: string
  capabilitiesVersion?: string
  role?: string
  displayName?: string
  summary?: string
  metadata?: Record<string, string | number | boolean>
  config?: CapabilityField[]
  actions?: CapabilityAction[]
  panels?: CapabilityPanel[]
  [key: string]: unknown
}

export interface RuntimeCapabilityEntry {
  manifest: CapabilityManifest
  instances: string[]
  updatedAt?: string
}

export type RuntimeCapabilitiesByVersion = Record<string, RuntimeCapabilityEntry>
export type RuntimeCapabilitiesByRole = Record<string, RuntimeCapabilitiesByVersion>
export type RuntimeCapabilitiesCatalogue = Record<string, RuntimeCapabilitiesByRole>

export interface RuntimeCapabilitiesCatalogueResponse {
  catalogue: RuntimeCapabilitiesCatalogue
}

export type ControllerRuntimeCapabilitiesSnapshot = RuntimeCapabilitiesByRole

import { useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { sendConfigUpdate } from '../../lib/orchestratorApi'
import type { Component } from '../../types/hive'
import type {
  CapabilityAction,
  CapabilityActionParameter,
  CapabilityField,
  CapabilityManifest,
  CapabilityOptionValue,
  CapabilityPanel,
  RuntimeCapabilitiesCatalogue,
  RuntimeCapabilityEntry,
} from '../../types/capabilities'
import { useRuntimeCapabilitiesStore, useUIStore } from '../../store'

interface Props {
  component: Component
}

interface ManifestSelection {
  version: string
  entry: RuntimeCapabilityEntry
}

type FieldValueMap = Record<string, unknown>

type JsonErrorMap = Record<string, string | null>

type JsonInputMap = Record<string, string>

export default function WorkerCapabilitiesPanel({ component }: Props) {
  const catalogue = useRuntimeCapabilitiesStore((state) => state.catalogue)
  const setToast = useUIStore((state) => state.setToast)
  const [saving, setSaving] = useState(false)
  const [formState, setFormState] = useState<FieldValueMap>({})
  const [rawJsonInputs, setRawJsonInputs] = useState<JsonInputMap>({})
  const [jsonErrors, setJsonErrors] = useState<JsonErrorMap>({})
  const [baseline, setBaseline] = useState<FieldValueMap>({})

  const selection = useMemo(() => selectManifest(catalogue, component), [catalogue, component])
  const manifest: CapabilityManifest | undefined = selection?.entry.manifest

  const configFields = useMemo(() => {
    if (!manifest || !Array.isArray(manifest.config)) return [] as CapabilityField[]
    return manifest.config.filter(isCapabilityField).sort(sortFields)
  }, [manifest])

  const groupedFields = useMemo(() => groupFields(configFields), [configFields])

  const actions = useMemo(() => {
    if (!manifest || !Array.isArray(manifest.actions)) return [] as CapabilityAction[]
    return manifest.actions.filter(isCapabilityAction)
  }, [manifest])

  const panels = useMemo(() => {
    if (!manifest || !Array.isArray(manifest.panels)) return [] as CapabilityPanel[]
    return manifest.panels.filter(isCapabilityPanel)
  }, [manifest])

  const derived = useMemo(
    () => deriveInitialState(configFields, component.config ?? {}),
    [configFields, component.config, component.id, component.version],
  )

  useEffect(() => {
    const initialValues = deepCloneRecord(derived.values)
    setFormState(initialValues)
    setRawJsonInputs({ ...derived.jsonInputs })
    setBaseline(initialValues)
    setJsonErrors({})
  }, [derived])

  const hasJsonError = useMemo(() => Object.values(jsonErrors).some(Boolean), [jsonErrors])
  const sanitizedValues = useMemo(() => sanitizeValues(configFields, formState), [configFields, formState])
  const isDirty = useMemo(() => !deepEqual(sanitizedValues, baseline), [sanitizedValues, baseline])

  if (!manifest) {
    return (
      <div className="space-y-3" data-testid="worker-capabilities-panel">
        <p>
          Worker capabilities manifest not available yet. The component will expose interactive controls once the swarm publishes
          its runtime catalogue.
        </p>
        {renderConfigPreview(component.config)}
      </div>
    )
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (saving) return
    if (hasJsonError) {
      setToast('Fix invalid JSON inputs before saving')
      return
    }
    const sanitizedNow = sanitizeValues(configFields, formState)
    const patch = diffRecords(sanitizedNow, baseline)
    if (Object.keys(patch).length === 0) {
      setToast('No changes to save')
      return
    }
    setSaving(true)
    try {
      await sendConfigUpdate(component, patch)
      const nextBaseline = deepCloneRecord(sanitizedNow)
      setBaseline(nextBaseline)
      setFormState(deepCloneRecord(sanitizedNow))
      setRawJsonInputs(buildJsonInputsFromValues(configFields, sanitizedNow))
      setJsonErrors({})
      setToast('Configuration update requested')
    } catch (error) {
      console.error('Failed to update worker configuration', error)
      setToast('Failed to update worker configuration')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6" data-testid="worker-capabilities-panel">
      <section className="space-y-2">
        <h3 className="text-lg font-semibold text-white">{manifest.displayName ?? component.name}</h3>
        {manifest.summary && <p className="text-sm text-white/70">{manifest.summary}</p>}
        <div className="flex flex-wrap gap-3 text-xs text-white/50">
          <span>Capabilities: {manifest.capabilitiesVersion ?? '—'}</span>
          <span>Role: {manifest.role ?? component.role}</span>
          <span>Worker version: {selection?.version ?? component.version ?? '—'}</span>
          <span>Instances tracked: {selection?.entry.instances?.length ?? 0}</span>
        </div>
      </section>

      {configFields.length > 0 ? (
        <form className="space-y-4" onSubmit={submit}>
          {groupedFields.map(([groupKey, fields]) => (
            <fieldset key={groupKey} className="space-y-3">
              <legend className="text-sm font-semibold text-white/80">
                {formatGroupLabel(groupKey, groupedFields.length)}
              </legend>
              <div className="space-y-3">
                {fields.map((field) => (
                  <ConfigField
                    key={field.name}
                    field={field}
                    value={formState[field.name]}
                    rawJson={rawJsonInputs[field.name] ?? ''}
                    error={jsonErrors[field.name] ?? null}
                    onBooleanChange={(next) =>
                      setFormState((prev) => ({ ...prev, [field.name]: next }))
                    }
                    onTextChange={(next) =>
                      setFormState((prev) => ({ ...prev, [field.name]: next }))
                    }
                    onNumberChange={(next) =>
                      setFormState((prev) => ({ ...prev, [field.name]: next }))
                    }
                    onSelectChange={(next) =>
                      setFormState((prev) => ({ ...prev, [field.name]: next }))
                    }
                    onMultiSelectChange={(next) =>
                      setFormState((prev) => ({ ...prev, [field.name]: next }))
                    }
                    onJsonChange={(text, parsed, error) => {
                      setRawJsonInputs((prev) => ({ ...prev, [field.name]: text }))
                      setJsonErrors((prev) => ({ ...prev, [field.name]: error }))
                      if (!error) {
                        setFormState((prev) => ({ ...prev, [field.name]: parsed }))
                      }
                    }}
                  />
                ))}
              </div>
            </fieldset>
          ))}
          <div className="flex items-center gap-3 pt-2">
            <button
              type="submit"
              className="rounded bg-sky-500/80 px-4 py-2 text-sm font-medium text-white transition hover:bg-sky-500 disabled:bg-white/20 disabled:text-white/40"
              disabled={saving || hasJsonError || !isDirty}
            >
              {saving ? 'Saving…' : 'Save changes'}
            </button>
            <button
              type="button"
              className="rounded border border-white/20 px-4 py-2 text-sm text-white/80 hover:bg-white/10"
              disabled={saving}
              onClick={() => {
                const resetValues = deepCloneRecord(baseline)
                setFormState(resetValues)
                setRawJsonInputs(buildJsonInputsFromValues(configFields, baseline))
                setJsonErrors({})
              }}
            >
              Reset
            </button>
            {hasJsonError && <span className="text-xs text-red-400">Resolve JSON errors to enable saving.</span>}
          </div>
        </form>
      ) : (
        <div className="rounded border border-white/10 bg-white/5 p-4 text-sm text-white/70">
          This worker does not expose configurable fields in its manifest.
        </div>
      )}

      {actions.length > 0 && (
        <section className="space-y-3">
          <h4 className="text-sm font-semibold text-white/80">Actions</h4>
          <p className="text-xs text-white/50">
            Action invocation will be introduced in a future release. The controls below describe available operations.
          </p>
          <div className="space-y-3">
            {actions.map((action) => (
              <div key={action.id} className="rounded border border-white/10 bg-white/5 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <div className="text-sm font-medium text-white">{action.label}</div>
                    {action.description && <p className="text-xs text-white/60">{action.description}</p>}
                  </div>
                  <button
                    type="button"
                    className="rounded bg-white/10 px-3 py-1 text-xs uppercase tracking-wide text-white/70"
                    disabled
                    aria-disabled="true"
                  >
                    {action.method} {action.endpoint}
                  </button>
                </div>
                {Array.isArray(action.params) && action.params.length > 0 && (
                  <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-white/60">
                    {action.params.filter(isCapabilityActionParam).map((param) => (
                      <li key={param.name}>
                        <span className="font-medium text-white/70">{param.label ?? param.name}</span>
                        {param.required ? ' (required)' : ''}
                        {param.description ? ` — ${param.description}` : ''}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {panels.length > 0 && (
        <section className="space-y-3">
          <h4 className="text-sm font-semibold text-white/80">Panels</h4>
          <div className="space-y-3">
            {panels.map((panel) => (
              <div key={panel.type} className="rounded border border-dashed border-white/10 bg-white/5 p-3 text-sm text-white/70">
                <div className="font-medium text-white">{panel.label ?? formatPanelType(panel.type)}</div>
                {panel.description && <p className="text-xs text-white/60">{panel.description}</p>}
                <div className="mt-1 text-xs text-white/40">Type: {panel.type}</div>
                {panel.source && <div className="mt-1 text-xs text-white/40">Source: {panel.source}</div>}
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

function selectManifest(
  catalogue: RuntimeCapabilitiesCatalogue,
  component: Component,
): ManifestSelection | null {
  const swarmId = normalizeSwarmId(component.swarmId)
  if (!swarmId) return null
  const bySwarm = catalogue[swarmId]
  if (!bySwarm) return null
  const roleKey = (component.role ?? '').trim()
  if (!roleKey) return null
  const roleEntry =
    bySwarm[roleKey] ??
    bySwarm[roleKey.toLowerCase()] ??
    Object.entries(bySwarm).find(([key]) => key.toLowerCase() === roleKey.toLowerCase())?.[1]
  if (!roleEntry) return null
  const versionKey = (component.version ?? '').trim()
  if (versionKey && roleEntry[versionKey]) {
    return { version: versionKey, entry: roleEntry[versionKey]! }
  }
  for (const [version, entry] of Object.entries(roleEntry)) {
    if (entry.instances?.some((instance) => instance === component.id)) {
      return { version, entry }
    }
  }
  const [firstVersion, firstEntry] = Object.entries(roleEntry)[0] ?? []
  if (firstVersion && firstEntry) {
    return { version: firstVersion, entry: firstEntry }
  }
  return null
}

function normalizeSwarmId(value: string | undefined): string | null {
  const trimmed = value?.trim()
  if (trimmed && trimmed.length > 0) return trimmed
  return 'default'
}

function deriveInitialState(
  fields: CapabilityField[],
  config: Record<string, unknown>,
): { values: FieldValueMap; jsonInputs: JsonInputMap } {
  const values: FieldValueMap = {}
  const jsonInputs: JsonInputMap = {}
  fields.forEach((field) => {
    const raw = config[field.name]
    const fallback = raw === undefined ? field.default : raw
    const normalized = normalizeFieldValue(field, fallback)
    values[field.name] = normalized
    if (isJsonField(field.type)) {
      jsonInputs[field.name] = normalized != null ? stringifyJson(normalized) : ''
    }
  })
  return { values, jsonInputs }
}

function normalizeFieldValue(field: CapabilityField, value: unknown): unknown {
  switch (field.type) {
    case 'boolean':
      return value === true
    case 'integer':
      if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value)
      if (typeof value === 'string' && value.trim()) {
        const parsed = Number.parseInt(value, 10)
        return Number.isNaN(parsed) ? null : parsed
      }
      return null
    case 'number':
      if (typeof value === 'number' && Number.isFinite(value)) return value
      if (typeof value === 'string' && value.trim()) {
        const parsed = Number.parseFloat(value)
        return Number.isNaN(parsed) ? null : parsed
      }
      return null
    case 'enum':
    case 'select':
      if (isCapabilityOptionValue(value)) return value
      if (typeof value === 'string') return value
      return null
    case 'multiselect':
      if (Array.isArray(value)) {
        return value.filter(isCapabilityOptionValue)
      }
      return []
    case 'json':
    case 'object':
    case 'array':
      if (value == null) return null
      if (typeof value === 'string') {
        try {
          return JSON.parse(value)
        } catch {
          return null
        }
      }
      if (typeof value === 'object') {
        return value
      }
      return null
    default:
      if (typeof value === 'string') return value
      if (value == null) return ''
      return String(value)
  }
}

function sanitizeValues(fields: CapabilityField[], state: FieldValueMap): FieldValueMap {
  const result: FieldValueMap = {}
  fields.forEach((field) => {
    result[field.name] = sanitizeValue(field, state[field.name])
  })
  return result
}

function sanitizeValue(field: CapabilityField, value: unknown): unknown {
  switch (field.type) {
    case 'boolean':
      return value === true
    case 'integer':
      return typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : null
    case 'number':
      return typeof value === 'number' && Number.isFinite(value) ? value : null
    case 'enum':
    case 'select':
      if (isCapabilityOptionValue(value)) return value
      if (typeof value === 'string') return value
      return null
    case 'multiselect':
      return Array.isArray(value) ? value.filter(isCapabilityOptionValue) : []
    case 'json':
    case 'object':
    case 'array':
      return value ?? null
    default:
      if (typeof value === 'string') return value
      if (value == null) return ''
      return String(value)
  }
}

function buildJsonInputsFromValues(fields: CapabilityField[], values: FieldValueMap): JsonInputMap {
  const inputs: JsonInputMap = {}
  fields.forEach((field) => {
    if (isJsonField(field.type)) {
      const current = values[field.name]
      inputs[field.name] = current != null ? stringifyJson(current) : ''
    }
  })
  return inputs
}

function diffRecords(current: FieldValueMap, baseline: FieldValueMap): Record<string, unknown> {
  const patch: Record<string, unknown> = {}
  const keys = new Set([...Object.keys(current), ...Object.keys(baseline)])
  keys.forEach((key) => {
    const a = current[key]
    const b = baseline[key]
    if (!deepEqual(a, b)) {
      patch[key] = a
    }
  })
  return patch
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return false
    for (let i = 0; i < a.length; i += 1) {
      if (!deepEqual(a[i], b[i])) return false
    }
    return true
  }
  if (isPlainObject(a) && isPlainObject(b)) {
    const aKeys = Object.keys(a)
    const bKeys = Object.keys(b)
    if (aKeys.length !== bKeys.length) return false
    const sortedA = [...aKeys].sort()
    const sortedB = [...bKeys].sort()
    for (let i = 0; i < sortedA.length; i += 1) {
      if (sortedA[i] !== sortedB[i]) return false
      if (!deepEqual((a as Record<string, unknown>)[sortedA[i]], (b as Record<string, unknown>)[sortedA[i]])) {
        return false
      }
    }
    return true
  }
  if (a == null || b == null) {
    return a === b
  }
  return false
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function deepCloneRecord(record: FieldValueMap): FieldValueMap {
  const result: FieldValueMap = {}
  Object.entries(record).forEach(([key, value]) => {
    result[key] = deepCloneValue(value)
  })
  return result
}

function deepCloneValue<T>(value: T): T {
  if (Array.isArray(value)) {
    return value.map((item) => deepCloneValue(item)) as unknown as T
  }
  if (isPlainObject(value)) {
    const clone: Record<string, unknown> = {}
    Object.entries(value).forEach(([key, val]) => {
      clone[key] = deepCloneValue(val)
    })
    return clone as T
  }
  return value
}

function groupFields(fields: CapabilityField[]): [string, CapabilityField[]][] {
  const groups = new Map<string, CapabilityField[]>()
  fields.forEach((field) => {
    const key = field.ui?.group?.trim() || '_default'
    const existing = groups.get(key)
    if (existing) {
      existing.push(field)
    } else {
      groups.set(key, [field])
    }
  })
  return Array.from(groups.entries())
}

function formatGroupLabel(groupKey: string, totalGroups: number): string {
  if (groupKey === '_default') {
    return totalGroups > 1 ? 'General' : 'Configuration'
  }
  return groupKey
    .replace(/[-_]/g, ' ')
    .replace(/\b\w/g, (char) => char.toUpperCase())
}

function formatPanelType(type: string): string {
  return type
    .replace(/[-_]/g, ' ')
    .replace(/\b\w/g, (char) => char.toUpperCase())
}

function stringifyJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return ''
  }
}

function renderConfigPreview(config: Record<string, unknown> | undefined) {
  const preview = extractConfigPreview(config)
  if (!preview) return null
  return (
    <div>
      <div className="mb-1 text-xs uppercase tracking-wide text-white/50">Current config</div>
      <pre className="max-h-64 overflow-auto rounded bg-black/30 p-3 text-xs text-white/80">{preview}</pre>
    </div>
  )
}

function extractConfigPreview(config: Record<string, unknown> | undefined): string | null {
  if (!config || typeof config !== 'object') return null
  const entries = Object.entries(config).filter(([key]) => key !== 'enabled')
  if (entries.length === 0) return null
  try {
    return JSON.stringify(Object.fromEntries(entries), null, 2)
  } catch {
    return null
  }
}

function isCapabilityField(value: unknown): value is CapabilityField {
  return (
    isPlainObject(value) &&
    typeof (value as CapabilityField).name === 'string' &&
    typeof (value as CapabilityField).type === 'string'
  )
}

function isCapabilityAction(value: unknown): value is CapabilityAction {
  return (
    isPlainObject(value) &&
    typeof (value as CapabilityAction).id === 'string' &&
    typeof (value as CapabilityAction).label === 'string' &&
    typeof (value as CapabilityAction).method === 'string' &&
    typeof (value as CapabilityAction).endpoint === 'string'
  )
}

function isCapabilityPanel(value: unknown): value is CapabilityPanel {
  return isPlainObject(value) && typeof (value as CapabilityPanel).type === 'string'
}

function isCapabilityActionParam(value: unknown): value is CapabilityActionParameter {
  return (
    isPlainObject(value) &&
    typeof (value as CapabilityActionParameter).name === 'string' &&
    typeof (value as CapabilityActionParameter).type === 'string'
  )
}

function isCapabilityOptionValue(value: unknown): value is CapabilityOptionValue {
  return (
    typeof value === 'string' ||
    typeof value === 'number' ||
    typeof value === 'boolean'
  )
}

function isJsonField(type: CapabilityField['type']): boolean {
  return type === 'json' || type === 'object' || type === 'array'
}

function sortFields(a: CapabilityField, b: CapabilityField) {
  const orderA = a.ui?.order ?? Number.POSITIVE_INFINITY
  const orderB = b.ui?.order ?? Number.POSITIVE_INFINITY
  if (orderA !== orderB) {
    return orderA - orderB
  }
  const labelA = a.label ?? a.name
  const labelB = b.label ?? b.name
  return labelA.localeCompare(labelB)
}

interface ConfigFieldProps {
  field: CapabilityField
  value: unknown
  rawJson: string
  error: string | null
  onBooleanChange: (value: boolean) => void
  onTextChange: (value: string) => void
  onNumberChange: (value: number | null) => void
  onSelectChange: (value: CapabilityOptionValue | null) => void
  onMultiSelectChange: (value: CapabilityOptionValue[]) => void
  onJsonChange: (raw: string, parsed: unknown, error: string | null) => void
}

function ConfigField({
  field,
  value,
  rawJson,
  error,
  onBooleanChange,
  onTextChange,
  onNumberChange,
  onSelectChange,
  onMultiSelectChange,
  onJsonChange,
}: ConfigFieldProps) {
  const label = field.label ?? field.name
  const description = field.description
  const inputId = `capability-${field.name}`

  const renderInput = () => {
    switch (field.type) {
      case 'boolean':
        return (
          <input
            id={inputId}
            type="checkbox"
            className="h-4 w-4"
            checked={value === true}
            onChange={(event) => onBooleanChange(event.target.checked)}
          />
        )
      case 'integer':
      case 'number': {
        const min = field.validation?.minimum ?? field.validation?.exclusiveMinimum
        const max = field.validation?.maximum ?? field.validation?.exclusiveMaximum
        return (
          <input
            id={inputId}
            type="number"
            className="w-full rounded border border-white/20 bg-black/20 px-3 py-2 text-sm text-white placeholder:text-white/40"
            value={typeof value === 'number' ? value : ''}
            onChange={(event) => {
              const text = event.target.value
              if (text === '') {
                onNumberChange(null)
                return
              }
              const parsed = field.type === 'integer' ? Number.parseInt(text, 10) : Number.parseFloat(text)
              if (Number.isNaN(parsed)) {
                return
              }
              onNumberChange(parsed)
            }}
            step={field.type === 'integer' ? 1 : 'any'}
            min={min}
            max={max}
          />
        )
      }
      case 'text':
        return (
          <textarea
            id={inputId}
            className={`w-full rounded border border-white/20 bg-black/20 px-3 py-2 text-sm text-white placeholder:text-white/40 ${
              field.ui?.monospace ? 'font-mono' : ''
            }`}
            rows={field.ui?.multiline ? 6 : 3}
            value={typeof value === 'string' ? value : ''}
            onChange={(event) => onTextChange(event.target.value)}
            placeholder={field.ui?.placeholder}
          />
        )
      case 'enum':
      case 'select': {
        const options = Array.isArray(field.options)
          ? field.options.filter((option) => isCapabilityOptionValue(option.value))
          : []
        if (options.length === 0) {
          return (
            <input
              id={inputId}
              type="text"
              className="w-full rounded border border-white/20 bg-black/20 px-3 py-2 text-sm text-white placeholder:text-white/40"
              value={typeof value === 'string' ? value : ''}
              onChange={(event) => onTextChange(event.target.value)}
              placeholder={field.ui?.placeholder}
            />
          )
        }
        const encodedValue = encodeOptionValue(
          isCapabilityOptionValue(value) || typeof value === 'string' ? (value as CapabilityOptionValue) : null,
        )
        return (
          <select
            id={inputId}
            className="w-full rounded border border-white/20 bg-black/20 px-3 py-2 text-sm text-white"
            value={encodedValue}
            onChange={(event) => {
              const decoded = decodeOptionValue(event.target.value)
              onSelectChange(decoded)
            }}
          >
            {!field.required && <option value="">Not set</option>}
            {options.map((option) => (
              <option key={encodeOptionValue(option.value)} value={encodeOptionValue(option.value)}>
                {option.label ?? String(option.value)}
              </option>
            ))}
          </select>
        )
      }
      case 'multiselect': {
        const options = Array.isArray(field.options)
          ? field.options.filter((option) => isCapabilityOptionValue(option.value))
          : []
        const selected = Array.isArray(value) ? value.filter(isCapabilityOptionValue) : []
        return (
          <select
            id={inputId}
            multiple
            className="w-full rounded border border-white/20 bg-black/20 px-3 py-2 text-sm text-white"
            value={selected.map((item) => encodeOptionValue(item))}
            onChange={(event) => {
              const selectedValues = Array.from(event.target.selectedOptions).map((option) =>
                decodeOptionValue(option.value),
              )
              onMultiSelectChange(selectedValues.filter(isCapabilityOptionValue))
            }}
          >
            {options.map((option) => (
              <option key={encodeOptionValue(option.value)} value={encodeOptionValue(option.value)}>
                {option.label ?? String(option.value)}
              </option>
            ))}
          </select>
        )
      }
      case 'json':
      case 'object':
      case 'array':
        return (
          <textarea
            id={inputId}
            className={`w-full rounded border border-white/20 bg-black/20 px-3 py-2 text-sm text-white placeholder:text-white/40 font-mono`}
            rows={field.ui?.multiline ? 8 : 6}
            value={rawJson}
            onChange={(event) => {
              const text = event.target.value
              if (!text.trim()) {
                onJsonChange('', null, null)
                return
              }
              try {
                const parsed = JSON.parse(text)
                onJsonChange(text, parsed, null)
              } catch {
                onJsonChange(text, null, 'Invalid JSON')
              }
            }}
            placeholder={field.ui?.placeholder}
          />
        )
      case 'duration':
      case 'cron':
      case 'string':
      default:
        return (
          <input
            id={inputId}
            type="text"
            className={`w-full rounded border border-white/20 bg-black/20 px-3 py-2 text-sm text-white placeholder:text-white/40 ${
              field.ui?.monospace ? 'font-mono' : ''
            }`}
            value={typeof value === 'string' ? value : ''}
            onChange={(event) => onTextChange(event.target.value)}
            placeholder={field.ui?.placeholder}
          />
        )
    }
  }

  return (
    <div className="space-y-2">
      <label htmlFor={inputId} className="block text-sm font-medium text-white">
        {label}
        {field.required ? <span className="ml-1 text-red-400">*</span> : null}
      </label>
      {description && <p className="text-xs text-white/60">{description}</p>}
      {renderInput()}
      {error && <p className="text-xs text-red-400">{error}</p>}
    </div>
  )
}

function encodeOptionValue(value: unknown): string {
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return JSON.stringify({ v: value })
  }
  return ''
}

function decodeOptionValue(encoded: string): CapabilityOptionValue | null {
  if (!encoded) return null
  try {
    const parsed = JSON.parse(encoded) as { v: CapabilityOptionValue }
    return parsed.v
  } catch {
    return null
  }
}

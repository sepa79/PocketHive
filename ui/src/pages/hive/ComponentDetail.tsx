import { useEffect, useMemo, useState, type Dispatch, type JSX, type SetStateAction } from 'react'
import type {
  CapabilityAction,
  CapabilityActionParameter,
  CapabilityConfigEntry,
  CapabilityManifest,
} from '../../types/capabilities'
import { normalizeManifests } from '../../lib/capabilities'
import type { Component } from '../../types/hive'
import { sendConfigUpdate } from '../../lib/orchestratorApi'
import QueuesPanel from './QueuesPanel'
import { heartbeatHealth, colorForHealth } from '../../lib/health'
import WiremockPanel from './WiremockPanel'

interface Props {
  component: Component
  onClose: () => void
}

type FormState = Record<string, string | boolean>
type ActionState = Record<string, FormState>

export default function ComponentDetail({ component, onClose }: Props) {
  const [toast, setToast] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>({})
  const [actionForms, setActionForms] = useState<ActionState>({})

  const manifest = useMemo(() => resolveManifest(component), [component])

  useEffect(() => {
    if (!manifest) {
      setForm({})
      return
    }
    const configRecord = component.config as Record<string, unknown> | undefined
    const initial: FormState = {}
    manifest.config.forEach((entry) => {
      const current = getConfigValue(configRecord, entry.name)
      const fallback = current !== undefined ? current : entry.default
      initial[entry.name] = formatValueForInput(entry, fallback)
    })
    setForm(initial)
  }, [component.id, component.config, manifest])

  useEffect(() => {
    if (!manifest) {
      setActionForms({})
      return
    }
    const next: ActionState = {}
    manifest.actions.forEach((action) => {
      const defaults: FormState = {}
      action.params.forEach((param) => {
        defaults[param.name] = formatValueForInput(param, param.default)
      })
      next[action.id] = defaults
    })
    setActionForms(next)
  }, [component.id, manifest])

  const showToast = (message: string) => {
    setToast(message)
    window.setTimeout(() => setToast(null), 3000)
  }

  const handleSubmit = async () => {
    if (!manifest) return
    const { payload, error } = buildPayloadFromEntries(manifest.config, form)
    if (error) {
      showToast(error)
      return
    }
    try {
      await sendConfigUpdate(component, payload)
      showToast('Config update sent')
    } catch {
      showToast('Config update failed')
    }
  }

  const handleAction = async (action: CapabilityAction) => {
    const state = actionForms[action.id] ?? {}
    const { payload, error } = buildPayloadFromEntries(action.params, state, {
      enforceRequired: true,
    })
    if (error) {
      showToast(error)
      return
    }
    try {
      await sendConfigUpdate(component, { action: { id: action.id, params: payload } })
      showToast('Action sent')
    } catch {
      showToast('Action failed')
    }
  }

  const health = heartbeatHealth(component.lastHeartbeat)
  const role = component.role.trim() || '—'
  const normalizedRole = component.role.trim().toLowerCase()
  const isWiremock = normalizedRole === 'wiremock'

  const renderedContent = isWiremock
    ? renderWiremock(component)
    : renderManifestDrivenPanel({
        manifest,
        form,
        setForm,
        actionForms,
        setActionForms,
        onAction: handleAction,
      })

  const containerClass = isWiremock
    ? 'mb-4'
    : 'p-4 border border-white/10 rounded mb-4 text-sm text-white/60 space-y-4'

  return (
    <div className="flex-1 p-4 overflow-y-auto relative">
      <button className="absolute top-2 right-2" onClick={onClose}>
        ×
      </button>
      <h2 className="text-xl mb-1 flex items-center gap-2">
        {component.id}
        <span className={`h-3 w-3 rounded-full ${colorForHealth(health)}`} />
      </h2>
      <div className="text-sm text-white/60 mb-3">{role}</div>
      <div className="space-y-1 text-sm mb-4">
        <div>Version: {component.version ?? '—'}</div>
        <div>Uptime: {formatSeconds(component.uptimeSec)}</div>
        <div>Last heartbeat: {timeAgo(component.lastHeartbeat)}</div>
        <div>Env: {component.env ?? '—'}</div>
        <div>
          Enabled:{' '}
          {component.config?.enabled === false
            ? 'false'
            : component.config?.enabled === true
            ? 'true'
            : '—'}
        </div>
      </div>
      <div className={containerClass}>{renderedContent}</div>
      {!isWiremock && manifest && manifest.config.length > 0 && (
        <button
          className="mb-4 rounded bg-blue-600 px-3 py-1 text-sm"
          onClick={handleSubmit}
        >
          Confirm
        </button>
      )}
      {toast && (
        <div className="fixed bottom-4 right-4 bg-black/80 text-white px-4 py-2 rounded">
          {toast}
        </div>
      )}
      {!isWiremock && (
        <>
          <h3 className="text-lg mb-2">Queues</h3>
          <QueuesPanel queues={component.queues} />
        </>
      )}
    </div>
  )
}

function renderWiremock(component: Component) {
  return <WiremockPanel component={component} />
}

function renderManifestDrivenPanel({
  manifest,
  form,
  setForm,
  actionForms,
  setActionForms,
  onAction,
}: {
  manifest: CapabilityManifest | null
  form: FormState
  setForm: Dispatch<SetStateAction<FormState>>
  actionForms: ActionState
  setActionForms: Dispatch<SetStateAction<ActionState>>
  onAction: (action: CapabilityAction) => void
}) {
  if (!manifest) {
    return <div className="text-white/50">Capability manifest not available for this component.</div>
  }

  return (
    <div className="space-y-6 text-white">
      <div className="space-y-3">
        <div className="uppercase text-[11px] tracking-wide text-white/50">Config</div>
        {manifest.config.length === 0 && <div className="text-white/50">No configurable options</div>}
        {manifest.config.map((entry) => {
          const currentValue = form[entry.name]
          return (
            <label key={entry.name} className="block space-y-1 text-sm text-white/80">
              <span className="block text-white">
                {getFieldLabel(entry)}
                <span className="text-white/40"> ({entry.type})</span>
              </span>
              {renderFieldControl(entry, currentValue, (value) =>
                setForm((prev) => ({ ...prev, [entry.name]: value })),
              )}
            </label>
          )
        })}
      </div>
      <div className="space-y-3">
        <div className="uppercase text-[11px] tracking-wide text-white/50">Actions</div>
        {manifest.actions.length === 0 && <div className="text-white/50">No actions available</div>}
        {manifest.actions.map((action) => {
          const params = actionForms[action.id] ?? {}
          return (
            <div key={action.id} className="space-y-2 rounded border border-white/10 p-3 text-sm text-white/80">
              <div className="font-medium text-white/90">{action.label}</div>
              {action.params.length > 0 && (
                <div className="space-y-2">
                  {action.params.map((param) => (
                    <label key={param.name} className="block space-y-1 text-xs text-white/70">
                      <span className="block text-white/80">
                        {getFieldLabel(param)}
                        <span className="text-white/40"> ({param.type})</span>
                        {param.required ? <span className="ml-1 text-rose-300">*</span> : null}
                      </span>
                      {renderFieldControl(param, params[param.name], (value) =>
                        setActionForms((prev) => {
                          const current = prev[action.id] ?? {}
                          return {
                            ...prev,
                            [action.id]: { ...current, [param.name]: value },
                          }
                        }),
                      )}
                    </label>
                  ))}
                </div>
              )}
              <button
                className="rounded bg-blue-700 px-3 py-1 text-xs"
                onClick={() => onAction(action)}
              >
                Run {action.label}
              </button>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function resolveManifest(component: Component): CapabilityManifest | null {
  if (component.capabilities) return component.capabilities
  const configRecord = component.config as Record<string, unknown> | undefined
  const raw = configRecord?.capabilities
  if (raw === undefined || raw === null) return null
  const list = Array.isArray(raw) ? raw : [raw]
  const [manifest] = normalizeManifests(list)
  return manifest ?? null
}

function getConfigValue(config: Record<string, unknown> | undefined, path: string): unknown {
  if (!config) return undefined
  if (Object.prototype.hasOwnProperty.call(config, path)) {
    return config[path]
  }
  if (!path.includes('.')) {
    return config[path]
  }
  const segments = path.split('.')
  let current: unknown = config
  for (const segment of segments) {
    if (!isPlainObject(current)) return undefined
    current = (current as Record<string, unknown>)[segment]
    if (current === undefined) return undefined
  }
  return current
}

function buildPayloadFromEntries(
  entries: (CapabilityConfigEntry | CapabilityActionParameter)[],
  state: FormState,
  options: { enforceRequired?: boolean } = {},
): { payload: Record<string, unknown>; error?: string } {
  const payload: Record<string, unknown> = {}
  for (const entry of entries) {
    const rawValue = state[entry.name]
    const { value, hasValue, error } = parseFieldValue(entry, rawValue)
    if (error) {
      return { payload: {}, error }
    }
    if (!hasValue) {
      if (options.enforceRequired && isRequired(entry)) {
        return { payload: {}, error: `${getFieldLabel(entry)} is required` }
      }
      continue
    }
    assignNestedValue(payload, entry.name, value)
  }
  return { payload }
}

function parseFieldValue(
  entry: CapabilityConfigEntry | CapabilityActionParameter,
  raw: string | boolean | undefined,
): { value: unknown; hasValue: boolean; error?: string } {
  const type = entry.type?.toLowerCase?.() ?? ''
  if (isBooleanType(type)) {
    if (typeof raw === 'boolean') {
      return { value: raw, hasValue: true }
    }
    if (typeof raw === 'string') {
      const normalized = raw.trim().toLowerCase()
      if (!normalized) return { value: false, hasValue: true }
      if (normalized === 'true') return { value: true, hasValue: true }
      if (normalized === 'false') return { value: false, hasValue: true }
    }
    return { value: Boolean(raw), hasValue: true }
  }
  if (isNumberType(type)) {
    const text = typeof raw === 'string' ? raw.trim() : raw === undefined || raw === null ? '' : String(raw)
    if (!text) return { value: undefined, hasValue: false }
    const num = Number(text)
    if (Number.isNaN(num)) {
      return { value: undefined, hasValue: false, error: `${getFieldLabel(entry)} must be a number` }
    }
    return { value: num, hasValue: true }
  }
  if (isJsonType(type)) {
    const text = typeof raw === 'string' ? raw.trim() : ''
    if (!text) return { value: undefined, hasValue: false }
    try {
      return { value: JSON.parse(text), hasValue: true }
    } catch {
      return { value: undefined, hasValue: false, error: `${getFieldLabel(entry)} must be valid JSON` }
    }
  }
  const value =
    typeof raw === 'string'
      ? raw
      : raw === undefined || raw === null
      ? ''
      : String(raw)
  return { value, hasValue: true }
}

function assignNestedValue(target: Record<string, unknown>, path: string, value: unknown) {
  if (!path.includes('.')) {
    target[path] = value
    return
  }
  const segments = path.split('.')
  const last = segments.pop()!
  let cursor: Record<string, unknown> = target
  for (const segment of segments) {
    const current = cursor[segment]
    if (!isPlainObject(current)) {
      cursor[segment] = {}
    }
    cursor = cursor[segment] as Record<string, unknown>
  }
  cursor[last] = value
}

function formatValueForInput(
  entry: CapabilityConfigEntry | CapabilityActionParameter,
  value: unknown,
): string | boolean {
  const type = entry.type?.toLowerCase?.() ?? ''
  if (isBooleanType(type)) {
    if (typeof value === 'boolean') return value
    if (typeof value === 'string') return value.trim().toLowerCase() === 'true'
    if (typeof value === 'number') return value !== 0
    return false
  }
  if (isJsonType(type)) {
    if (typeof value === 'string') return value
    if (value === undefined || value === null) return ''
    try {
      return JSON.stringify(value, null, 2)
    } catch {
      return ''
    }
  }
  if (typeof value === 'number') return String(value)
  if (typeof value === 'string') return value
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (value === undefined || value === null) return ''
  try {
    return JSON.stringify(value)
  } catch {
    return ''
  }
}

function renderFieldControl(
  entry: CapabilityConfigEntry | CapabilityActionParameter,
  rawValue: string | boolean | undefined,
  onChange: (value: string | boolean) => void,
): JSX.Element {
  const type = entry.type?.toLowerCase?.() ?? ''
  if (isBooleanType(type)) {
    const checked = typeof rawValue === 'boolean' ? rawValue : rawValue === 'true'
    return (
      <input
        className="h-4 w-4 rounded border border-white/30 bg-transparent"
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
    )
  }

  const value =
    typeof rawValue === 'string'
      ? rawValue
      : rawValue === undefined || rawValue === null
      ? ''
      : String(rawValue)

  if (shouldUseTextarea(entry, type)) {
    return (
      <textarea
        className="w-full rounded bg-white/10 px-2 py-1 text-white"
        value={value}
        rows={4}
        onChange={(e) => onChange(e.target.value)}
      />
    )
  }

  const step = extractStep(entry)
  const min = 'min' in entry ? entry.min : undefined
  const max = 'max' in entry ? entry.max : undefined
  return (
    <input
      className="w-full rounded bg-white/10 px-2 py-1 text-white"
      type={isNumberType(type) ? 'number' : 'text'}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      min={min}
      max={max}
      step={step}
    />
  )
}

function shouldUseTextarea(
  entry: CapabilityConfigEntry | CapabilityActionParameter,
  type: string,
) {
  if ('multiline' in entry && entry.multiline) return true
  return type === 'text' || isJsonType(type)
}

function extractStep(entry: CapabilityConfigEntry | CapabilityActionParameter) {
  const ui = entry.ui
  if (!ui || typeof ui !== 'object') return undefined
  const step = (ui as Record<string, unknown>).step
  return typeof step === 'number' ? step : undefined
}

function getFieldLabel(entry: CapabilityConfigEntry | CapabilityActionParameter) {
  const ui = entry.ui
  if (ui && typeof ui === 'object') {
    const label = (ui as Record<string, unknown>).label
    if (typeof label === 'string' && label.trim().length > 0) {
      return label.trim()
    }
  }
  return entry.name
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isBooleanType(type: string) {
  return type === 'boolean' || type === 'bool'
}

function isNumberType(type: string) {
  return type === 'number' || type === 'int' || type === 'integer' || type === 'float' || type === 'double'
}

function isJsonType(type: string) {
  return type === 'json' || type === 'object'
}

function isRequired(entry: CapabilityConfigEntry | CapabilityActionParameter) {
  return 'required' in entry && entry.required === true
}

function formatSeconds(sec?: number) {
  if (sec == null) return '—'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  return `${h}h ${m}m ${s}s`
}

function timeAgo(ts: number) {
  const diff = Math.floor((Date.now() - ts) / 1000)
  return `${diff}s ago`
}

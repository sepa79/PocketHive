import { useEffect, useMemo, useRef, useState, type JSX } from 'react'
import type { Component } from '../../types/hive'
import { sendConfigUpdate } from '../../lib/orchestratorApi'
import QueuesPanel from './QueuesPanel'
import { heartbeatHealth, colorForHealth } from '../../lib/health'
import WiremockPanel from './WiremockPanel'
import { useCapabilities } from '../../contexts/CapabilitiesContext'
import type { CapabilityConfigEntry } from '../../types/capabilities'
import { formatCapabilityValue, inferCapabilityInputType } from '../../lib/capabilities'

interface Props {
  component: Component
  onClose: () => void
}

type ConfigFormValue = string | boolean | undefined

export default function ComponentDetail({ component, onClose }: Props) {
  const [toast, setToast] = useState<string | null>(null)
  const [isEditing, setIsEditing] = useState(false)
  const [form, setForm] = useState<Record<string, ConfigFormValue>>({})
  const { ensureCapabilities, getManifestForRole } = useCapabilities()
  const manifest = useMemo(
    () => getManifestForRole(component.role),
    [component.role, getManifestForRole],
  )
  const previousComponentIdRef = useRef(component.id)

  useEffect(() => {
    void ensureCapabilities()
  }, [ensureCapabilities])

  useEffect(() => {
    const previousId = previousComponentIdRef.current
    const idChanged = component.id !== previousId
    if (isEditing && !idChanged) {
      return
    }
    previousComponentIdRef.current = component.id
    if (!manifest) {
      setForm({})
      return
    }
    const cfg = isRecord(component.config) ? component.config : undefined
    const next: Record<string, ConfigFormValue> = {}
    manifest.config.forEach((entry) => {
      next[entry.name] = computeInitialValue(entry, cfg)
    })
    setForm(next)
  }, [component.id, component.config, manifest, isEditing])

  const handleSubmit = async () => {
    if (!manifest) {
      displayToast(setToast, 'Capability manifest not available for this component')
      return
    }
    const cfg: Record<string, unknown> = {}
    for (const entry of manifest.config) {
      const result = convertFormValue(entry, form[entry.name])
      if (!result.ok) {
        displayToast(setToast, result.message)
        return
      }
      if (result.apply) {
        assignNestedValue(cfg, entry.name, result.value)
      }
    }
    try {
      await sendConfigUpdate(component, cfg)
      displayToast(setToast, 'Config update sent')
      setIsEditing(false)
    } catch {
      displayToast(setToast, 'Config update failed')
    }
  }

  const single = async () => {
    try {
      await sendConfigUpdate(component, { singleRequest: true })
      displayToast(setToast, 'Config update sent')
    } catch {
      displayToast(setToast, 'Config update failed')
    }
  }

  const health = heartbeatHealth(component.lastHeartbeat)
  const role = component.role.trim() || '—'
  const normalizedRole = component.role.trim().toLowerCase()
  const isWiremock = normalizedRole === 'wiremock'

  const configEntries = manifest?.config ?? []
  const renderedContent = isWiremock ? (
    <WiremockPanel component={component} />
  ) : manifest ? (
    <div className="space-y-2">
      {configEntries.length === 0 ? (
        <div className="text-white/50">No configurable options</div>
      ) : (
        configEntries.map((entry) => (
          <ConfigEntryRow
            key={entry.name}
            entry={entry}
            value={form[entry.name]}
            disabled={!isEditing}
            onChange={(value) =>
              setForm((prev) => ({
                ...prev,
                [entry.name]: value,
              }))
            }
          />
        ))
      )}
    </div>
  ) : (
    <div className="text-white/60">Capability manifest not available for this component.</div>
  )
  const containerClass = isWiremock
    ? 'mb-4'
    : 'p-4 border border-white/10 rounded mb-4 text-sm text-white/60 space-y-2'

  return (
    <div className="flex-1 p-4 overflow-y-auto relative">
      <button
        className="absolute top-2 right-2"
        onClick={() => {
          setIsEditing(false)
          onClose()
        }}
      >
        ×
      </button>
      <div className="flex items-center justify-between mb-1">
        <h2 className="text-xl flex items-center gap-2">
          {component.id}
          <span className={`h-3 w-3 rounded-full ${colorForHealth(health)}`} />
          {/* status refresh no longer supported */}
        </h2>
      </div>
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
      {!isWiremock && configEntries.length > 0 && (
        <div className="mb-2 flex items-center justify-between text-xs text-white/60">
          <span className="uppercase tracking-wide text-white/50">Configuration</span>
          <label className="flex items-center gap-3 cursor-pointer select-none">
            <span className="text-white/70">{isEditing ? 'Unlocked' : 'Locked'}</span>
            <span className="relative inline-flex h-5 w-9 items-center">
              <input
                type="checkbox"
                className="peer sr-only"
                checked={isEditing}
                aria-label={isEditing ? 'Disable editing' : 'Enable editing'}
                onChange={(event) => setIsEditing(event.target.checked)}
              />
              <span className="h-5 w-9 rounded-full bg-white/25 transition-colors peer-checked:bg-blue-500" />
              <span className="absolute left-1 top-1 h-3 w-3 rounded-full bg-white transition-transform duration-200 peer-checked:translate-x-4" />
            </span>
          </label>
        </div>
      )}
      <div className={containerClass}>{renderedContent}</div>
      {!isWiremock && configEntries.length > 0 && (
        <button
          className="mb-4 rounded bg-blue-600 px-3 py-1 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
          onClick={handleSubmit}
          disabled={!isEditing}
        >
          Confirm
        </button>
      )}
      {!isWiremock && (normalizedRole === 'generator' || normalizedRole === 'trigger') && (
        <button
          className="mb-4 rounded bg-blue-700 px-3 py-1 text-xs"
          onClick={single}
        >
          {normalizedRole === 'trigger' ? 'Single trigger' : 'Single request'}
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

interface ConfigEntryRowProps {
  entry: CapabilityConfigEntry
  value: ConfigFormValue
  disabled: boolean
  onChange: (value: string | boolean) => void
}

function ConfigEntryRow({ entry, value, disabled, onChange }: ConfigEntryRowProps) {
  const unit = extractUnit(entry)
  const labelSuffix = `${entry.type || 'string'}${unit ? ` • ${unit}` : ''}`
  const content = renderConfigInput(entry, value, disabled, onChange)
  return (
    <label className="block space-y-1">
      <span className="block text-white/70">
        {entry.name}
        <span className="text-white/40"> ({labelSuffix})</span>
      </span>
      {content}
      {typeof entry.min === 'number' || typeof entry.max === 'number' ? (
        <span className="block text-[11px] text-white/40">
          {typeof entry.min === 'number' ? `min ${entry.min}` : ''}
          {typeof entry.min === 'number' && typeof entry.max === 'number' ? ' · ' : ''}
          {typeof entry.max === 'number' ? `max ${entry.max}` : ''}
        </span>
      ) : null}
      {((entry.type || '').toLowerCase() === 'json' || entry.multiline) && (
        <span className="block text-[11px] text-white/40">
          {((entry.type || '').toLowerCase() === 'json'
            ? 'Value must be valid JSON.'
            : 'Multiline input supported.')}
        </span>
      )}
    </label>
  )
}

function renderConfigInput(
  entry: CapabilityConfigEntry,
  rawValue: ConfigFormValue,
  disabled: boolean,
  onChange: (value: string | boolean) => void,
): JSX.Element {
  const normalizedType = (entry.type || '').toLowerCase()
  if (normalizedType === 'boolean' || normalizedType === 'bool') {
    const checked = rawValue === true
    return (
      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          className="h-4 w-4 accent-blue-500"
          checked={checked}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
        />
        <span className="text-white/60 text-xs">Enabled</span>
      </div>
    )
  }

  const value = typeof rawValue === 'string' ? rawValue : ''
  if (entry.multiline || normalizedType === 'text' || normalizedType === 'json') {
    return (
      <textarea
        className="w-full rounded bg-white/10 px-2 py-1 text-white"
        value={value}
        rows={normalizedType === 'json' ? 4 : 3}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    )
  }

  const inputType = inferCapabilityInputType(entry.type)
  const step = extractStep(entry)
  return (
    <input
      className="w-full rounded bg-white/10 px-2 py-1 text-white"
      type={inputType}
      value={value}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
      min={typeof entry.min === 'number' ? entry.min : undefined}
      max={typeof entry.max === 'number' ? entry.max : undefined}
      step={step}
    />
  )
}

function computeInitialValue(
  entry: CapabilityConfigEntry,
  config: Record<string, unknown> | undefined,
): ConfigFormValue {
  const existing = getValueForPath(config, entry.name)
  const source = existing !== undefined ? existing : entry.default
  return formatValueForInput(entry, source)
}

function getValueForPath(
  config: Record<string, unknown> | undefined,
  path: string,
): unknown {
  if (!config || !path) return undefined
  const segments = path.split('.').filter((segment) => segment.length > 0)
  let current: unknown = config
  for (const segment of segments) {
    if (!isRecord(current)) {
      return undefined
    }
    current = current[segment]
  }
  return current
}

function formatValueForInput(entry: CapabilityConfigEntry, value: unknown): ConfigFormValue {
  const normalizedType = (entry.type || '').toLowerCase()
  if (normalizedType === 'boolean' || normalizedType === 'bool') {
    return coerceBoolean(value)
  }
  if (normalizedType === 'json') {
    return formatJsonValue(value)
  }
  if (normalizedType === 'number' || normalizedType === 'int' || normalizedType === 'integer') {
    if (typeof value === 'number') return Number.isFinite(value) ? String(value) : ''
    if (typeof value === 'string') return value
    return ''
  }
  return formatCapabilityValue(value)
}

function coerceBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true') return true
    if (normalized === 'false') return false
  }
  return false
}

function formatJsonValue(value: unknown): string {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return ''
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return ''
  }
}

type ConversionResult =
  | { ok: true; apply: boolean; value: unknown }
  | { ok: false; message: string }

function convertFormValue(entry: CapabilityConfigEntry, rawValue: ConfigFormValue): ConversionResult {
  const normalizedType = (entry.type || '').toLowerCase()
  if (normalizedType === 'boolean' || normalizedType === 'bool') {
    return { ok: true, apply: true, value: rawValue === true }
  }
  if (normalizedType === 'json') {
    const str = typeof rawValue === 'string' ? rawValue.trim() : ''
    if (!str) {
      return { ok: true, apply: false, value: undefined }
    }
    try {
      return { ok: true, apply: true, value: JSON.parse(str) }
    } catch {
      return { ok: false, message: `Invalid JSON for ${entry.name}` }
    }
  }
  if (normalizedType === 'number' || normalizedType === 'int' || normalizedType === 'integer') {
    const str = typeof rawValue === 'string' ? rawValue.trim() : ''
    if (!str) {
      return { ok: true, apply: false, value: undefined }
    }
    const num = Number(str)
    if (Number.isNaN(num)) {
      return { ok: false, message: `${entry.name} must be a number` }
    }
    return { ok: true, apply: true, value: num }
  }
  if (typeof rawValue === 'string') {
    const trimmed = rawValue.trim()
    if (!trimmed) {
      return { ok: true, apply: false, value: undefined }
    }
    return { ok: true, apply: true, value: rawValue }
  }
  return { ok: true, apply: false, value: undefined }
}

function assignNestedValue(target: Record<string, unknown>, path: string, value: unknown) {
  const segments = path.split('.').filter((segment) => segment.length > 0)
  if (segments.length === 0) return
  let cursor: Record<string, unknown> = target
  for (let i = 0; i < segments.length - 1; i++) {
    const key = segments[i]!
    const next = cursor[key]
    if (!isRecord(next)) {
      const created: Record<string, unknown> = {}
      cursor[key] = created
      cursor = created
    } else {
      cursor = next
    }
  }
  cursor[segments[segments.length - 1]!] = value
}

function displayToast(setter: (value: string | null) => void, message: string) {
  setter(message)
  window.setTimeout(() => setter(null), 3000)
}

function extractUnit(entry: CapabilityConfigEntry): string | null {
  if (!entry.ui || typeof entry.ui !== 'object') return null
  const value = (entry.ui as Record<string, unknown>).unit
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function extractStep(entry: CapabilityConfigEntry): number | undefined {
  if (!entry.ui || typeof entry.ui !== 'object') return undefined
  const value = (entry.ui as Record<string, unknown>).step
  return typeof value === 'number' ? value : undefined
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
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


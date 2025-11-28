import { useEffect, useMemo, useRef, useState, type JSX } from 'react'
import type { Component } from '../../types/hive'
import { sendConfigUpdate } from '../../lib/orchestratorApi'
import QueuesPanel from './QueuesPanel'
import { heartbeatHealth, colorForHealth } from '../../lib/health'
import WiremockPanel from './WiremockPanel'
import { useCapabilities } from '../../contexts/CapabilitiesContext'
import type { CapabilityConfigEntry } from '../../types/capabilities'
import { formatCapabilityValue, inferCapabilityInputType } from '../../lib/capabilities'
import { useSwarmMetadata } from '../../contexts/SwarmMetadataContext'

interface Props {
  component: Component
  onClose: () => void
}

type ConfigFormValue = string | boolean | undefined

export default function ComponentDetail({ component, onClose }: Props) {
  const [toast, setToast] = useState<string | null>(null)
  const [isEditing, setIsEditing] = useState(false)
  const [form, setForm] = useState<Record<string, ConfigFormValue>>({})
   const [showRefreshTooltip, setShowRefreshTooltip] = useState(false)
  const { ensureCapabilities, getManifestForImage, manifests } = useCapabilities()
  const { ensureSwarms, refreshSwarms, getBeeImage, getControllerImage, findSwarm } =
    useSwarmMetadata()
  const resolvedImage = useMemo(() => {
    if (component.image) {
      return component.image
    }
    const swarmKey = component.swarmId?.trim()
    if (!swarmKey) {
      return null
    }
    const normalizedRole = component.role?.trim().toLowerCase()
    if (normalizedRole === 'swarm-controller') {
      return getControllerImage(swarmKey)
    }
    return getBeeImage(swarmKey, component.role)
  }, [
    component.image,
    component.role,
    component.swarmId,
    getBeeImage,
    getControllerImage,
  ])
  const manifest = useMemo(
    () => getManifestForImage(resolvedImage),
    [resolvedImage, getManifestForImage],
  )
  const previousComponentIdRef = useRef(component.id)
  const refreshTooltipTimer = useRef<number | null>(null)

  useEffect(() => {
    void ensureCapabilities()
  }, [ensureCapabilities])

  useEffect(() => {
    void ensureSwarms()
  }, [ensureSwarms])

  const handleRefreshMouseEnter = () => {
    if (refreshTooltipTimer.current != null) {
      return
    }
    refreshTooltipTimer.current = window.setTimeout(() => {
      setShowRefreshTooltip(true)
    }, 5000)
  }

  const handleRefreshMouseLeave = () => {
    if (refreshTooltipTimer.current != null) {
      window.clearTimeout(refreshTooltipTimer.current)
      refreshTooltipTimer.current = null
    }
    setShowRefreshTooltip(false)
  }

  const handleSubmit = async () => {
    if (!manifest) {
      displayToast(setToast, 'Capability manifest not available for this component')
      return
    }
    const cfg: Record<string, unknown> = {}
    // Use merged worker + IO entries so IO changes (e.g. ratePerSec) are sent as part of the patch.
    for (const entry of effectiveConfigEntries) {
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

  const health = heartbeatHealth(component.lastHeartbeat)
  const role = component.role.trim() || '—'
  const normalizedRole = component.role.trim().toLowerCase()
  const isWiremock = normalizedRole === 'wiremock'
  const componentConfig =
    component.config && typeof component.config === 'object'
      ? (component.config as Record<string, unknown>)
      : undefined

  const runtimeEntries = useMemo(() => {
    const cfg = componentConfig
    if (!cfg) return [] as { label: string; value: string }[]
    const entries: { label: string; value: string }[] = []
    // Orchestrator-specific runtime metadata
    if (normalizedRole === 'orchestrator') {
      const adapter = getString(cfg.computeAdapter)
      if (adapter) {
        entries.push({
          label: 'Compute adapter',
          value: adapter,
        })
      }
    }
    // Swarm-controller specific metadata based on swarm summaries + guard diagnostics
    if (normalizedRole === 'swarm-controller') {
      const swarmSummary = findSwarm(component.swarmId ?? null)
      if (swarmSummary?.templateId) {
        entries.push({
          label: 'Scenario template',
          value: swarmSummary.templateId,
        })
      }
      if (swarmSummary?.stackName) {
        entries.push({
          label: 'Swarm stack',
          value: swarmSummary.stackName,
        })
      }
      const guard =
        cfg && cfg.bufferGuard && typeof cfg.bufferGuard === 'object'
          ? (cfg.bufferGuard as Record<string, unknown>)
          : undefined
      if (guard) {
        const guardActive = getBoolean(guard.active)
        const guardProblem = getString(guard.problem)
        if (guardActive !== undefined) {
          entries.push({
            label: 'Buffer guard',
            value: guardActive ? 'active' : 'inactive',
          })
        }
        if (guardProblem) {
          entries.push({
            label: 'Guard problem',
            value: guardProblem,
          })
        }
      }
    }
    const tps = getNumber(cfg.tps)
    const intervalSeconds = getNumber(cfg.intervalSeconds)
    const transactions = getNumber(cfg.transactions)
    const successRatio = getNumber(cfg.successRatio)
    const avgLatencyMs = getNumber(cfg.avgLatencyMs)
    const httpMode = getString(cfg.httpMode)
    const httpThreadCount = getNumber(cfg.httpThreadCount)
    const httpMaxConnections = getNumber(cfg.httpMaxConnections)
    if (tps !== undefined) {
      entries.push({ label: 'TPS', value: Math.round(tps).toString() })
    }
    if (intervalSeconds !== undefined && intervalSeconds > 0) {
      entries.push({
        label: 'Interval (s)',
        value: intervalSeconds.toFixed(intervalSeconds >= 10 ? 0 : 1),
      })
    }
    if (transactions !== undefined) {
      entries.push({ label: 'Transactions', value: transactions.toString() })
    }
    if (successRatio !== undefined) {
      entries.push({
        label: 'Success ratio',
        value: successRatio.toFixed(3),
      })
    }
    if (avgLatencyMs !== undefined) {
      entries.push({
        label: 'Avg latency (ms)',
        value: avgLatencyMs.toFixed(1),
      })
    }
    if (httpMode) {
      entries.push({ label: 'HTTP mode', value: httpMode })
    }
    if (httpThreadCount !== undefined) {
      entries.push({
        label: 'HTTP thread count',
        value: httpThreadCount.toString(),
      })
    }
    if (httpMaxConnections !== undefined) {
      entries.push({
        label: 'HTTP max connections',
        value: httpMaxConnections.toString(),
      })
    }
    // Scheduler finite-run/runtime config (if present)
    const inputs =
      cfg && cfg.inputs && typeof cfg.inputs === 'object'
        ? (cfg.inputs as Record<string, unknown>)
        : undefined
    const scheduler =
      inputs && inputs.scheduler && typeof inputs.scheduler === 'object'
        ? (inputs.scheduler as Record<string, unknown>)
        : undefined
    const schedRate = scheduler ? getNumber(scheduler.ratePerSec) : undefined
    const schedMax = scheduler ? getNumber(scheduler.maxMessages) : undefined
    if (schedRate !== undefined) {
      entries.push({
        label: 'Scheduler rate (msg/s)',
        value: schedRate.toString(),
      })
    }
    if (schedMax !== undefined && schedMax > 0) {
      entries.push({
        label: 'Scheduler max messages',
        value: schedMax.toString(),
      })
    }
    // Scheduler runtime diagnostics (if present in status data)
    const schedulerDiag =
      cfg && cfg.scheduler && typeof cfg.scheduler === 'object'
        ? (cfg.scheduler as Record<string, unknown>)
        : undefined
    if (schedulerDiag) {
      const dispatched = getNumber(schedulerDiag.dispatched)
      const remaining = getNumber(schedulerDiag.remaining)
      const exhausted = getBoolean(schedulerDiag.exhausted)
      if (dispatched !== undefined) {
        entries.push({
          label: 'Scheduler dispatched',
          value: dispatched.toString(),
        })
      }
      if (remaining !== undefined && remaining >= 0) {
        entries.push({
          label: 'Scheduler remaining',
          value: remaining.toString(),
        })
      }
      if (exhausted !== undefined) {
        entries.push({
          label: 'Scheduler exhausted',
          value: exhausted ? 'true' : 'false',
        })
      }
    }
    // Redis dataset runtime diagnostics (if present in status data)
    const redisDiag =
      cfg && cfg.redisDataset && typeof cfg.redisDataset === 'object'
        ? (cfg.redisDataset as Record<string, unknown>)
        : undefined
    if (redisDiag) {
      const listName = getString(redisDiag.listName)
      const rate = getNumber(redisDiag.ratePerSec)
      const dispatched = getNumber(redisDiag.dispatched)
      const lastPopAt = getString(redisDiag.lastPopAt)
      const lastEmptyAt = getString(redisDiag.lastEmptyAt)
      const lastErrorAt = getString(redisDiag.lastErrorAt)
      const lastErrorMessage = getString(redisDiag.lastErrorMessage)
      if (listName) {
        entries.push({
          label: 'Redis list',
          value: listName,
        })
      }
      if (rate !== undefined) {
        entries.push({
          label: 'Redis rate (msg/s)',
          value: rate.toString(),
        })
      }
      if (dispatched !== undefined) {
        entries.push({
          label: 'Redis dispatched',
          value: dispatched.toString(),
        })
      }
      if (lastPopAt) {
        entries.push({
          label: 'Last Redis pop at',
          value: lastPopAt,
        })
      }
      if (lastEmptyAt) {
        entries.push({
          label: 'Last Redis empty at',
          value: lastEmptyAt,
        })
      }
      if (lastErrorAt) {
        entries.push({
          label: 'Last Redis error at',
          value: lastErrorAt,
        })
      }
      if (lastErrorMessage) {
        entries.push({
          label: 'Last Redis error',
          value: lastErrorMessage,
        })
      }
    }
    return entries
  }, [component.config])

  const effectiveConfigEntries: CapabilityConfigEntry[] = useMemo(() => {
    if (!manifest) return []
    const baseEntries = manifest.config ?? []

    const cfg = componentConfig
    const inputs =
      cfg && cfg.inputs && typeof cfg.inputs === 'object'
        ? (cfg.inputs as Record<string, unknown>)
        : undefined
    const inputType =
      typeof inputs?.type === 'string' ? inputs.type.trim().toUpperCase() : undefined

    const allEntries: CapabilityConfigEntry[] = [...baseEntries]

    // Merge IO capabilities for the current input type, if any
    if (inputType) {
      const ioEntries: CapabilityConfigEntry[] = []
      for (const m of manifests) {
        const ui = m.ui as Record<string, unknown> | undefined
        const ioTypeRaw = ui && typeof ui.ioType === 'string' ? ui.ioType : undefined
        const ioType = ioTypeRaw ? ioTypeRaw.trim().toUpperCase() : undefined
        if (ioType && ioType === inputType && Array.isArray(m.config)) {
          ioEntries.push(...m.config)
        }
      }
      allEntries.push(...ioEntries)
    }

    // De-duplicate by config name (first entry wins)
    const byName = new Map<string, CapabilityConfigEntry>()
    for (const entry of allEntries) {
      if (!byName.has(entry.name)) {
        byName.set(entry.name, entry)
      }
    }

    // Apply simple conditional support based on entry.when (if present)
    const merged = Array.from(byName.values())
    if (!cfg) return merged

    return merged.filter((entry) => {
      const when = entry.when
      if (!when || typeof when !== 'object') {
        return true
      }
      const requiredInputTypeRaw = when['inputs.type']
      const requiredInputType =
        typeof requiredInputTypeRaw === 'string'
          ? requiredInputTypeRaw.trim().toUpperCase()
          : undefined
      if (requiredInputType && inputType && requiredInputType !== inputType) {
        return false
      }
      return true
    })
  }, [manifest, manifests, componentConfig])

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
    // Use the merged worker + IO config entries when initialising the form,
    // so IO fields (like ratePerSec) pick up existing values from config.
    effectiveConfigEntries.forEach((entry) => {
      next[entry.name] = computeInitialValue(entry, cfg)
    })
    setForm(next)
  }, [component.id, component.config, manifest, isEditing, effectiveConfigEntries])

  const renderedContent = isWiremock ? (
    <WiremockPanel component={component} />
  ) : manifest ? (
    <div className="space-y-2">
      {effectiveConfigEntries.length === 0 ? (
        <div className="text-white/50">No configurable options</div>
      ) : (
        effectiveConfigEntries.map((entry) => (
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
        </h2>
        {normalizedRole === 'orchestrator' && (
          <div className="relative">
            <button
              className="rounded border border-white/20 px-2 py-0.5 text-xs text-white/80 hover:bg-white/10"
              onClick={async () => {
                try {
                  await refreshSwarms()
                  displayToast(setToast, 'Swarm metadata refreshed')
                } catch {
                  displayToast(setToast, 'Failed to refresh swarm metadata')
                } finally {
                  handleRefreshMouseLeave()
                }
              }}
              onMouseEnter={handleRefreshMouseEnter}
              onMouseLeave={handleRefreshMouseLeave}
            >
              Refresh swarms
            </button>
            {showRefreshTooltip && (
              <div className="absolute right-0 mt-1 w-64 rounded border border-white/20 bg-black/80 p-2 text-[11px] text-white/90 z-10">
                bringOutYourDead() — removes FAILED swarms from registry so you can recreate
                deleted swarms.
              </div>
            )}
          </div>
        )}
      </div>
      <div className="text-sm text-white/60 mb-3">{role}</div>
      <div className="space-y-1 text-sm mb-4">
        <div>Version: {component.version ?? '—'}</div>
        <div>
          Started:{' '}
          {typeof component.startedAt === 'number'
            ? new Date(component.startedAt).toLocaleString()
            : '—'}
        </div>
        <div>Last heartbeat: {timeAgo(component.lastHeartbeat)}</div>
        <div>
          Enabled:{' '}
          {component.config?.enabled === false
            ? 'false'
            : component.config?.enabled === true
            ? 'true'
            : '—'}
        </div>
      </div>
      {runtimeEntries.length > 0 && (
        <div className="space-y-1 text-sm mb-4">
          <div className="text-white/70 font-semibold">Runtime</div>
          {runtimeEntries.map((entry) => (
            <div key={entry.label} className="flex justify-between">
              <span className="text-white/60">{entry.label}</span>
              <span className="text-white/90">{entry.value}</span>
            </div>
          ))}
        </div>
      )}
      {!isWiremock && effectiveConfigEntries.length > 0 && (
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
      {!isWiremock && effectiveConfigEntries.length > 0 && (
        <button
          className="mb-4 rounded bg-blue-600 px-3 py-1 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
          onClick={handleSubmit}
          disabled={!isEditing}
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
  const options = Array.isArray(entry.options) ? entry.options : undefined

  if (options && options.length > 0) {
    const value = typeof rawValue === 'string' ? rawValue : formatCapabilityValue(entry.default)
    return (
      <select
        className="w-full rounded bg-white/10 px-2 py-1 text-white"
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      >
        {options.map((option, index) => {
          const label = formatCapabilityValue(option)
          return (
            <option key={index} value={label}>
              {label}
            </option>
          )
        })}
      </select>
    )
  }

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
  const useSlider = inputType === 'number' && typeof entry.min === 'number' && typeof entry.max === 'number'

  if (useSlider) {
    const numericValue =
      value === '' ? (typeof entry.default === 'number' ? entry.default : entry.min ?? 0) : Number(value)
    const displayValue = Number.isFinite(numericValue) ? String(numericValue) : ''
    return (
      <div className="space-y-1">
        <input
          className="w-full"
          type="range"
          value={Number.isFinite(numericValue) ? numericValue : 0}
          disabled={disabled}
          min={typeof entry.min === 'number' ? entry.min : undefined}
          max={typeof entry.max === 'number' ? entry.max : undefined}
          step={step}
          onChange={(event) => onChange(event.target.value)}
        />
        <div className="flex items-center justify-between gap-2 text-xs text-white/70">
          <span>
            {typeof entry.min === 'number' ? entry.min : ''}
            {typeof entry.min === 'number' && typeof entry.max === 'number' ? ' – ' : ''}
            {typeof entry.max === 'number' ? entry.max : ''}
          </span>
          <input
            className="w-20 rounded bg-white/10 px-2 py-0.5 text-right text-xs text-white"
            type="number"
            value={displayValue}
            disabled={disabled}
            min={typeof entry.min === 'number' ? entry.min : undefined}
            max={typeof entry.max === 'number' ? entry.max : undefined}
            step={step}
            onChange={(event) => onChange(event.target.value)}
          />
        </div>
      </div>
    )
  }

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

function getNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  return undefined
}

function getString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

function getBoolean(value: unknown): boolean | undefined {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true') return true
    if (normalized === 'false') return false
  }
  return undefined
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

function timeAgo(ts: number) {
  const diff = Math.floor((Date.now() - ts) / 1000)
  return `${diff}s ago`
}

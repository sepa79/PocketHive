import { useEffect, useMemo, useRef, useState } from 'react'
import {
  capabilityEntryUiString,
  formatCapabilityValue,
  groupCapabilityConfigEntries,
  matchesCapabilityWhen,
  type CapabilityConfigEntry,
} from '../../lib/capabilities'

type ConfigFormValue = string | boolean

type ConfigUpdatePatchModalProps = {
  open: boolean
  title: string
  imageLabel: string
  entries: CapabilityConfigEntry[]
  currentConfig: Record<string, unknown> | null
  currentConfigAvailable: boolean
  busy?: boolean
  onClose: () => void
  onApply: (patch: Record<string, unknown>) => void | Promise<void>
}

type ConversionResult =
  | { ok: true; apply: boolean; value: unknown }
  | { ok: false; message: string }

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function getValueForPath(config: Record<string, unknown> | null | undefined, path: string): unknown {
  if (!config || !path) return undefined
  const segments = path.split('.').filter((segment) => segment.length > 0)
  let current: unknown = config
  for (const segment of segments) {
    if (!isPlainObject(current)) {
      return undefined
    }
    current = current[segment]
  }
  return current
}

function assignNestedValue(target: Record<string, unknown>, path: string, value: unknown) {
  const segments = path.split('.').filter((segment) => segment.length > 0)
  if (segments.length === 0) return
  let cursor: Record<string, unknown> = target
  for (let index = 0; index < segments.length - 1; index += 1) {
    const key = segments[index]
    if (!key) continue
    const next = cursor[key]
    if (!isPlainObject(next)) {
      const created: Record<string, unknown> = {}
      cursor[key] = created
      cursor = created
    } else {
      cursor = next
    }
  }
  const last = segments[segments.length - 1]
  if (last) {
    cursor[last] = value
  }
}

function formatValueForInput(entry: CapabilityConfigEntry, value: unknown): ConfigFormValue {
  const normalizedType = entry.type.trim().toLowerCase()
  if (normalizedType === 'boolean' || normalizedType === 'bool') {
    if (typeof value === 'boolean') return value
    if (typeof value === 'number') return value !== 0
    if (typeof value === 'string') {
      const normalized = value.trim().toLowerCase()
      if (normalized === 'true') return true
      if (normalized === 'false') return false
    }
    return false
  }
  if (normalizedType === 'json') {
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
  if (normalizedType === 'number' || normalizedType === 'int' || normalizedType === 'integer') {
    if (typeof value === 'number') return Number.isFinite(value) ? String(value) : ''
    if (typeof value === 'string') return value
    return ''
  }
  return formatCapabilityValue(value)
}

function convertFormValue(entry: CapabilityConfigEntry, rawValue: ConfigFormValue): ConversionResult {
  const normalizedType = entry.type.trim().toLowerCase()
  if (normalizedType === 'boolean' || normalizedType === 'bool') {
    return { ok: true, apply: true, value: rawValue === true }
  }
  if (normalizedType === 'json') {
    const text = typeof rawValue === 'string' ? rawValue.trim() : ''
    if (!text) return { ok: true, apply: false, value: undefined }
    try {
      return { ok: true, apply: true, value: JSON.parse(text) }
    } catch {
      return { ok: false, message: `${entry.name} must be valid JSON.` }
    }
  }
  if (normalizedType === 'number' || normalizedType === 'int' || normalizedType === 'integer') {
    const text = typeof rawValue === 'string' ? rawValue.trim() : ''
    if (!text) return { ok: true, apply: false, value: undefined }
    const parsed = Number(text)
    if (!Number.isFinite(parsed)) {
      return { ok: false, message: `${entry.name} must be a number.` }
    }
    return { ok: true, apply: true, value: parsed }
  }
  if (typeof rawValue === 'string') {
    if (!rawValue.trim()) return { ok: true, apply: false, value: undefined }
    return { ok: true, apply: true, value: rawValue }
  }
  return { ok: true, apply: false, value: undefined }
}

function extractUnit(entry: CapabilityConfigEntry): string | null {
  if (!entry.ui || typeof entry.ui !== 'object') return null
  const value = (entry.ui as Record<string, unknown>).unit
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

function extractStep(entry: CapabilityConfigEntry): number | undefined {
  if (!entry.ui || typeof entry.ui !== 'object') return undefined
  const value = (entry.ui as Record<string, unknown>).step
  return typeof value === 'number' ? value : undefined
}

export function ConfigUpdatePatchModal({
  open,
  title,
  imageLabel,
  entries,
  currentConfig,
  currentConfigAvailable,
  busy = false,
  onClose,
  onApply,
}: ConfigUpdatePatchModalProps) {
  const [form, setForm] = useState<Record<string, ConfigFormValue>>({})
  const [enabled, setEnabled] = useState<Record<string, boolean>>({})
  const [error, setError] = useState<string | null>(null)
  const [activeGroup, setActiveGroup] = useState('General')
  const [search, setSearch] = useState('')
  const [onlyIncluded, setOnlyIncluded] = useState(false)
  const initKeyRef = useRef('')

  useEffect(() => {
    if (!open) {
      initKeyRef.current = ''
      return
    }
    const nextKey = `${imageLabel}:${entries.map((entry) => entry.name).join('|')}`
    if (initKeyRef.current === nextKey) return
    initKeyRef.current = nextKey
    const nextForm: Record<string, ConfigFormValue> = {}
    const nextEnabled: Record<string, boolean> = {}
    for (const entry of entries) {
      const currentValue = currentConfigAvailable ? getValueForPath(currentConfig, entry.name) : undefined
      const displaySource = currentValue !== undefined ? currentValue : entry.default
      nextForm[entry.name] = formatValueForInput(entry, displaySource)
      nextEnabled[entry.name] = false
    }
    setForm(nextForm)
    setEnabled(nextEnabled)
    setError(null)
    setActiveGroup('General')
    setSearch('')
    setOnlyIncluded(false)
  }, [currentConfig, currentConfigAvailable, entries, imageLabel, open])

  const visibleEntries = useMemo(() => {
    const resolveWhenValue = (path: string): unknown => {
      if (path in form) return form[path]
      return currentConfigAvailable ? getValueForPath(currentConfig, path) : undefined
    }
    return entries.filter((entry) => matchesCapabilityWhen(entry.when, resolveWhenValue))
  }, [currentConfig, currentConfigAvailable, entries, form])

  const filteredEntries = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return visibleEntries.filter((entry) => {
      if (onlyIncluded && enabled[entry.name] !== true) return false
      if (!needle) return true
      const label = capabilityEntryUiString(entry, 'label') ?? ''
      const group = capabilityEntryUiString(entry, 'group') ?? ''
      return (
        entry.name.toLowerCase().includes(needle) ||
        label.toLowerCase().includes(needle) ||
        group.toLowerCase().includes(needle)
      )
    })
  }, [enabled, onlyIncluded, search, visibleEntries])

  const groups = useMemo(() => groupCapabilityConfigEntries(filteredEntries), [filteredEntries])

  useEffect(() => {
    if (!open || groups.length === 0) return
    if (!groups.some((group) => group.id === activeGroup)) {
      setActiveGroup(groups[0]?.id ?? 'General')
    }
  }, [activeGroup, groups, open])

  const handleApply = () => {
    const patch: Record<string, unknown> = {}
    const resolveWhenValue = (path: string): unknown => {
      if (path in form) return form[path]
      return currentConfigAvailable ? getValueForPath(currentConfig, path) : undefined
    }
    for (const entry of entries) {
      if (!matchesCapabilityWhen(entry.when, resolveWhenValue)) continue
      if (enabled[entry.name] !== true) continue
      const result = convertFormValue(entry, form[entry.name] ?? '')
      if (!result.ok) {
        setError(result.message)
        return
      }
      if (result.apply) {
        assignNestedValue(patch, entry.name, result.value)
      }
    }
    if (Object.keys(patch).length === 0) {
      setError('No config fields selected for this patch.')
      return
    }
    setError(null)
    void onApply(patch)
  }

  if (!open) return null

  return (
    <div className="modalBackdrop" role="presentation" onClick={() => (!busy ? onClose() : undefined)}>
      <div className="modal configPatchModal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="modalHeader">
          <div>
            <div className="h2">{title}</div>
            <div className="muted">Image: {imageLabel || '—'}</div>
          </div>
          <button type="button" className="actionButton actionButtonGhost" onClick={onClose} disabled={busy}>
            Close
          </button>
        </div>

	        <div className={currentConfigAvailable ? 'configPatchBanner configPatchBannerOk' : 'configPatchBanner'}>
	          {currentConfigAvailable
	            ? 'Current values are from worker status-full data.config.'
	            : 'Current runtime config is unavailable. Values below are capability defaults; only selected fields are sent.'}
	        </div>

        <div className="configPatchToolbar">
          <input
            className="textInput configPatchSearch"
            value={search}
            placeholder="Search config fields"
            onChange={(event) => setSearch(event.currentTarget.value)}
          />
	          <label className="configPatchToggle">
	            <input
	              type="checkbox"
	              checked={onlyIncluded}
	              onChange={(event) => setOnlyIncluded(event.currentTarget.checked)}
	            />
	            <span>Selected only</span>
	          </label>
          {search ? (
            <button type="button" className="actionButton actionButtonGhost actionButtonTiny" onClick={() => setSearch('')}>
              Clear
            </button>
          ) : null}
        </div>

        {groups.length > 1 ? (
          <div className="configPatchTabs">
            {groups.map((group) => (
              <button
                key={group.id}
                type="button"
                className={group.id === activeGroup ? 'detailTabButton detailTabButtonActive' : 'detailTabButton'}
                onClick={() => setActiveGroup(group.id)}
              >
                {group.label}
              </button>
            ))}
          </div>
        ) : null}

        <div className="configPatchFields">
          {groups.length === 0 ? (
            <div className="muted">No matching config fields.</div>
          ) : (
            groups
              .filter((group) => groups.length === 1 || group.id === activeGroup)
              .flatMap((group) =>
                group.entries.map((entry) => {
                  const label = capabilityEntryUiString(entry, 'label') ?? entry.name
                  const help = capabilityEntryUiString(entry, 'help')
                  const unit = extractUnit(entry)
                  const normalizedType = entry.type.trim().toLowerCase()
                  const isIncluded = enabled[entry.name] === true
                  const value = form[entry.name]
                  const currentValue = currentConfigAvailable ? getValueForPath(currentConfig, entry.name) : undefined
                  const hasCurrentValue = currentValue !== undefined
                  const setValue = (nextValue: ConfigFormValue) => {
                    setEnabled((previous) => ({ ...previous, [entry.name]: true }))
                    setForm((previous) => ({ ...previous, [entry.name]: nextValue }))
                  }
                  const fieldClass = isIncluded ? 'textInput configPatchInput' : 'textInput configPatchInput configPatchInputMuted'

                  let field
                  if (entry.options && entry.options.length > 0) {
                    const selected = typeof value === 'string' ? value : ''
                    field = (
                      <select className={fieldClass} value={selected} onChange={(event) => setValue(event.currentTarget.value)}>
                        {entry.options.map((option, index) => {
                          const optionValue =
                            option === null || option === undefined
                              ? ''
                              : typeof option === 'string'
                                ? option
                                : JSON.stringify(option)
                          return (
                            <option key={`${entry.name}-${index}`} value={optionValue}>
                              {optionValue || '—'}
                            </option>
                          )
                        })}
                      </select>
                    )
                  } else if (normalizedType === 'boolean' || normalizedType === 'bool') {
                    field = (
                      <label className="configPatchBoolean">
                        <input
                          type="checkbox"
                          checked={value === true}
                          onChange={(event) => setValue(event.currentTarget.checked)}
                        />
                        <span>{value === true ? 'true' : 'false'}</span>
                      </label>
                    )
                  } else if (normalizedType === 'json' || entry.multiline || normalizedType === 'text') {
                    field = (
                      <textarea
                        className={`${fieldClass} configPatchTextarea`}
                        rows={normalizedType === 'json' ? 8 : 5}
                        value={typeof value === 'string' ? value : ''}
                        onChange={(event) => setValue(event.currentTarget.value)}
                      />
                    )
                  } else {
                    field = (
                      <input
                        className={fieldClass}
                        type={normalizedType === 'number' || normalizedType === 'int' || normalizedType === 'integer' ? 'number' : 'text'}
                        min={entry.min}
                        max={entry.max}
                        step={extractStep(entry)}
                        value={typeof value === 'string' ? value : ''}
                        onChange={(event) => setValue(event.currentTarget.value)}
                      />
                    )
                  }

                  return (
                    <div key={entry.name} className={isIncluded ? 'configPatchField configPatchFieldIncluded' : 'configPatchField'}>
	                      <div className="configPatchFieldHeader">
	                        <label className="configPatchInclude">
		                          <input
		                            type="checkbox"
		                            aria-label={`Include ${label} in patch`}
		                            checked={isIncluded}
		                            onChange={(event) => {
		                              const checked = event.currentTarget.checked
		                              setEnabled((previous) => ({ ...previous, [entry.name]: checked }))
		                            }}
		                          />
	                          <span className="configPatchIncludeText">
	                            <span>{label}</span>
	                            <span className="configPatchIncludeHint">include in patch</span>
	                          </span>
	                        </label>
                        <span className="muted">
                          {entry.type}
                          {unit ? ` / ${unit}` : ''}
                        </span>
                      </div>
                      {label !== entry.name ? <div className="configPatchPath">{entry.name}</div> : null}
                      {help ? <div className="muted">{help}</div> : null}
                      {field}
                      <div className="configPatchFieldFooter">
                        <span className="muted">
                          {hasCurrentValue ? 'current value loaded' : currentConfigAvailable ? 'no current value' : 'current unavailable'}
                        </span>
                        {(typeof entry.min === 'number' || typeof entry.max === 'number') ? (
                          <span className="muted">
                            {typeof entry.min === 'number' ? `min ${entry.min}` : ''}
                            {typeof entry.min === 'number' && typeof entry.max === 'number' ? ' / ' : ''}
                            {typeof entry.max === 'number' ? `max ${entry.max}` : ''}
                          </span>
                        ) : null}
                      </div>
                    </div>
                  )
                }),
              )
          )}
        </div>

        {error ? <div className="configPatchError">{error}</div> : null}

        <div className="configPatchActions">
          <button type="button" className="actionButton actionButtonGhost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="actionButton" onClick={handleApply} disabled={busy}>
            {busy ? 'Sending…' : 'Send config update'}
          </button>
        </div>
      </div>
    </div>
  )
}

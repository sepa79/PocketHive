import { useEffect, useMemo, useRef, useState } from 'react'
import type * as React from 'react'
import type { editor as MonacoEditor } from 'monaco-editor'
import Editor from '@monaco-editor/react'
import { createPortal } from 'react-dom'
import type { CapabilityConfigEntry } from '../types/capabilities'
import {
  capabilityEntryUiString,
  groupCapabilityConfigEntries,
  inferCapabilityInputType,
  matchesCapabilityWhen,
} from '../lib/capabilities'
import { parseWeightedTemplate, WeightedChoiceEditor } from './WeightedChoiceEditor'

type ConfigFormValue = string | boolean

type ValueEditorState = {
  entry: CapabilityConfigEntry
  label: string
  value: string
  language: 'json' | 'yaml' | 'plaintext'
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function getValueForPath(
  config: Record<string, unknown> | undefined,
  path: string,
): unknown {
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
  for (let i = 0; i < segments.length - 1; i += 1) {
    const key = segments[i]!
    const next = cursor[key]
    if (!isPlainObject(next)) {
      const created: Record<string, unknown> = {}
      cursor[key] = created
      cursor = created
    } else {
      cursor = next
    }
  }
  cursor[segments[segments.length - 1]!] = value
}

function formatValueForInput(entry: CapabilityConfigEntry, value: unknown): ConfigFormValue {
  const normalizedType = (entry.type || '').toLowerCase()
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
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
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

export function ConfigUpdatePatchModal({
  open,
  imageLabel,
  entries,
  baseConfig,
  existingPatch,
  onClose,
  onApply,
}: {
  open: boolean
  imageLabel: string
  entries: CapabilityConfigEntry[]
  baseConfig: Record<string, unknown> | undefined
  existingPatch: Record<string, unknown> | undefined
  onClose: () => void
  onApply: (patch: Record<string, unknown> | undefined) => void
}): React.ReactElement | null {
  const [form, setForm] = useState<Record<string, ConfigFormValue>>({})
  const [enabled, setEnabled] = useState<Record<string, boolean>>({})
  const [error, setError] = useState<string | null>(null)
  const [activeGroup, setActiveGroup] = useState<string>('General')
  const [search, setSearch] = useState<string>('')
  const [onlyOverridden, setOnlyOverridden] = useState(false)
  const [fullscreen, setFullscreen] = useState(false)
  const [valueEditorState, setValueEditorState] = useState<ValueEditorState | null>(null)
  const [valueEditorError, setValueEditorError] = useState<string | null>(null)
  const valueEditorRef = useRef<MonacoEditor.IStandaloneCodeEditor | null>(null)

  useEffect(() => {
    if (!open) return
    const nextForm: Record<string, ConfigFormValue> = {}
    const nextEnabled: Record<string, boolean> = {}
    for (const entry of entries) {
      const overrideValue = existingPatch ? getValueForPath(existingPatch, entry.name) : undefined
      const currentValue =
        baseConfig && overrideValue === undefined ? getValueForPath(baseConfig, entry.name) : undefined
      if (overrideValue !== undefined) {
        nextEnabled[entry.name] = true
        nextForm[entry.name] = formatValueForInput(entry, overrideValue)
      } else {
        nextEnabled[entry.name] = false
        const displaySource = currentValue !== undefined ? currentValue : entry.default
        nextForm[entry.name] = formatValueForInput(entry, displaySource)
      }
    }
    setForm(nextForm)
    setEnabled(nextEnabled)
    setError(null)
    setActiveGroup('General')
    setSearch('')
    setOnlyOverridden(false)
    setFullscreen(false)
    setValueEditorState(null)
    setValueEditorError(null)
  }, [open, entries, baseConfig, existingPatch])

  const visibleEntries = useMemo(() => {
    const base = baseConfig && isPlainObject(baseConfig) ? baseConfig : undefined
    const resolveWhenValue = (path: string): unknown => {
      if (path in form) {
        return form[path]
      }
      return getValueForPath(base, path)
    }
    return entries.filter((entry) => matchesCapabilityWhen(entry.when, resolveWhenValue))
  }, [baseConfig, entries, form])

  const filteredEntries = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return visibleEntries.filter((entry) => {
      if (onlyOverridden && enabled[entry.name] !== true) {
        return false
      }
      if (!needle) return true
      const label = capabilityEntryUiString(entry, 'label') ?? ''
      const group = capabilityEntryUiString(entry, 'group') ?? ''
      return (
        entry.name.toLowerCase().includes(needle) ||
        label.toLowerCase().includes(needle) ||
        group.toLowerCase().includes(needle)
      )
    })
  }, [visibleEntries, search, onlyOverridden, enabled])

  const groups = useMemo(() => groupCapabilityConfigEntries(filteredEntries), [filteredEntries])

  useEffect(() => {
    if (!open) return
    if (groups.length === 0) return
    if (!groups.some((group) => group.id === activeGroup)) {
      setActiveGroup(groups[0]!.id)
    }
  }, [open, groups, activeGroup])

  const closeAndReset = () => {
    onClose()
    setValueEditorState(null)
    setValueEditorError(null)
  }

  const handleApply = () => {
    const patch: Record<string, unknown> = {}
    const base = baseConfig && isPlainObject(baseConfig) ? baseConfig : undefined
    const resolveWhenValue = (path: string): unknown => {
      if (path in form) {
        return form[path]
      }
      return getValueForPath(base, path)
    }
    for (const entry of entries) {
      if (!matchesCapabilityWhen(entry.when, resolveWhenValue)) continue
      if (enabled[entry.name] !== true) continue
      const raw = form[entry.name]
      const result = convertFormValue(entry, raw)
      if (!result.ok) {
        setError(result.message)
        return
      }
      if (result.apply) {
        assignNestedValue(patch, entry.name, result.value)
      }
    }
    const hasConfig = Object.keys(patch).length > 0
    onApply(hasConfig ? patch : undefined)
  }

  if (!open) return null
  if (typeof document === 'undefined') return null

  return createPortal(
    <>
      <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/70">
        <div
          role="dialog"
          aria-modal="true"
          className={
            fullscreen
              ? 'w-[96vw] h-[92vh] rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white flex flex-col'
              : 'w-full max-w-3xl h-[85vh] rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white flex flex-col'
          }
        >
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-xs font-semibold text-white/80">Edit config-update patch</h3>
            <div className="flex items-center gap-2">
              <button
                type="button"
                className="rounded border border-white/10 bg-white/5 px-2 py-1 text-[11px] text-white/80 hover:bg-white/10"
                onClick={() => setFullscreen((prev) => !prev)}
              >
                {fullscreen ? 'Exit fullscreen' : 'Fullscreen'}
              </button>
              <button type="button" className="text-white/60 hover:text-white" onClick={closeAndReset}>
                ×
              </button>
            </div>
          </div>

          <div className="mb-2 text-[11px] text-white/60">
            Image: <span className="font-mono text-white/80">{imageLabel || '(unknown)'}</span>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
            <input
              className="min-w-[220px] flex-1 rounded border border-white/10 bg-white/5 px-2 py-1 text-[11px] text-white/90 placeholder:text-white/40"
              placeholder="Search (label / path / group)…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <label className="inline-flex items-center gap-2 text-[11px] text-white/70">
              <input
                type="checkbox"
                className="h-3 w-3 accent-blue-500"
                checked={onlyOverridden}
                onChange={(e) => setOnlyOverridden(e.target.checked)}
              />
              Only overridden
            </label>
            {search.trim() !== '' && (
              <button
                type="button"
                className="rounded border border-white/10 bg-white/5 px-2 py-1 text-[11px] text-white/70 hover:bg-white/10"
                onClick={() => setSearch('')}
              >
                Clear
              </button>
            )}
          </div>

          {groups.length > 1 && (
            <div className="flex flex-wrap gap-2 mb-2">
              {groups.map((group) => (
                <button
                  key={group.id}
                  type="button"
                  className={
                    group.id === activeGroup
                      ? 'rounded border border-white/30 bg-white/10 px-2 py-1 text-[11px] text-white'
                      : 'rounded border border-white/10 px-2 py-1 text-[11px] text-white/70 hover:bg-white/5'
                  }
                  onClick={() => setActiveGroup(group.id)}
                >
                  {group.label}
                </button>
              ))}
            </div>
          )}

          <div className="flex-1 overflow-y-auto space-y-2 mb-3">
            {groups.length === 0 ? (
              <div className="text-[11px] text-white/50">No matching fields</div>
            ) : (
              groups
                .filter((group) => groups.length === 1 || group.id === activeGroup)
                .map((group) => (
                  <div key={group.id} className="space-y-2">
                    {group.entries.map((entry) => {
                      const unit = extractUnit(entry)
                      const labelSuffix = `${entry.type || 'string'}${unit ? ` • ${unit}` : ''}`
                      const label = capabilityEntryUiString(entry, 'label') ?? entry.name
                      const help = capabilityEntryUiString(entry, 'help')
                      const showPath = label !== entry.name
                      const normalizedType = (entry.type || '').toLowerCase()
                      const options = Array.isArray(entry.options) ? entry.options : undefined
                      const isLargeEditable =
                        entry.multiline || normalizedType === 'text' || normalizedType === 'json'

                      const rawValue = form[entry.name]
                      const baseValue = baseConfig ? getValueForPath(baseConfig, entry.name) : undefined
                      const baseDisplay = formatValueForInput(entry, baseValue !== undefined ? baseValue : entry.default)
                      const effectiveValue = rawValue !== undefined ? rawValue : baseDisplay
                      const isEnabled = enabled[entry.name] === true
                      const weightedTemplateModel =
                        typeof effectiveValue === 'string' ? parseWeightedTemplate(effectiveValue) : null

                      const updateValue = (value: ConfigFormValue) => {
                        setEnabled((prev) => ({ ...prev, [entry.name]: true }))
                        setForm((prev) => ({ ...prev, [entry.name]: value }))
                      }

                      const openValueEditor = () => {
                        const value = typeof effectiveValue === 'string' ? effectiveValue : ''
                        const language: ValueEditorState['language'] =
                          normalizedType === 'json'
                            ? 'json'
                            : normalizedType === 'yaml'
                              ? 'yaml'
                              : 'plaintext'
                        setValueEditorState({ entry, label, value, language })
                        setValueEditorError(null)
                      }

                      let field: React.ReactElement
                      if (options && options.length > 0) {
                        const value = typeof effectiveValue === 'string' ? effectiveValue : ''
                        field = (
                          <select
                            className={
                              isEnabled
                                ? 'w-full rounded bg-white/10 px-2 py-1 text-white text-xs'
                                : 'w-full rounded bg-white/5 px-2 py-1 text-white/80 text-xs'
                            }
                            value={value}
                            onChange={(event) => updateValue(event.target.value)}
                          >
                            {options.map((option, index) => {
                              const label =
                                option === null || option === undefined
                                  ? ''
                                  : typeof option === 'string'
                                    ? option
                                    : JSON.stringify(option)
                              return (
                                <option key={index} value={label}>
                                  {label}
                                </option>
                              )
                            })}
                          </select>
                        )
                      } else if (normalizedType === 'boolean' || normalizedType === 'bool') {
                        const checked = effectiveValue === true
                        field = (
                          <label
                            className={
                              isEnabled
                                ? 'inline-flex items-center gap-2 text-xs text-white/80'
                                : 'inline-flex items-center gap-2 text-xs text-white/60'
                            }
                          >
                            <input
                              type="checkbox"
                              className="h-4 w-4 accent-blue-500"
                              checked={checked}
                              onChange={(event) => updateValue(event.target.checked)}
                            />
                            <span>Enabled</span>
                          </label>
                        )
                      } else if (weightedTemplateModel) {
                        const current = typeof effectiveValue === 'string' ? effectiveValue : ''
                        field = (
                          <WeightedChoiceEditor
                            value={current}
                            model={weightedTemplateModel}
                            onChange={(next) => updateValue(next)}
                          />
                        )
                      } else if (
                        inferCapabilityInputType(entry.type) === 'number' &&
                        typeof entry.min === 'number' &&
                        typeof entry.max === 'number'
                      ) {
                        const value = typeof effectiveValue === 'string' ? effectiveValue : ''
                        const numericValue =
                          value === '' ? (typeof entry.default === 'number' ? entry.default : entry.min ?? 0) : Number(value)
                        const displayValue = Number.isFinite(numericValue) ? String(numericValue) : ''
                        const step = extractStep(entry)
                        field = (
                          <div className="space-y-1">
                            <input
                              className="w-full"
                              type="range"
                              value={Number.isFinite(numericValue) ? numericValue : 0}
                              min={entry.min}
                              max={entry.max}
                              step={step}
                              onChange={(event) => updateValue(event.target.value)}
                            />
                            <div className="flex items-center justify-between gap-2 text-[11px] text-white/60">
                              <span>
                                {entry.min} – {entry.max}
                              </span>
                              <input
                                className={
                                  isEnabled
                                    ? 'w-20 rounded bg-white/10 px-2 py-0.5 text-right text-[11px] text-white'
                                    : 'w-20 rounded bg-white/5 px-2 py-0.5 text-right text-[11px] text-white/80'
                                }
                                type="number"
                                value={displayValue}
                                min={entry.min}
                                max={entry.max}
                                step={step}
                                onChange={(event) => updateValue(event.target.value)}
                              />
                            </div>
                          </div>
                        )
                      } else if (isLargeEditable) {
                        const value = typeof effectiveValue === 'string' ? effectiveValue : ''
                        field = (
                          <div className="space-y-1">
                            <textarea
                              className={
                                isEnabled
                                  ? 'w-full rounded bg-white/10 px-2 py-1 text-white text-xs font-mono'
                                  : 'w-full rounded bg-white/5 px-2 py-1 text-white/80 text-xs font-mono'
                              }
                              rows={normalizedType === 'json' ? 4 : 3}
                              value={value}
                              onChange={(event) => updateValue(event.target.value)}
                            />
                            <div className="flex justify-end">
                              <button
                                type="button"
                                className="rounded border border-white/10 bg-white/5 px-2 py-1 text-[11px] text-white/70 hover:bg-white/10 disabled:opacity-50"
                                onClick={openValueEditor}
                              >
                                Open editor
                              </button>
                            </div>
                          </div>
                        )
                      } else {
                        const value = typeof effectiveValue === 'string' ? effectiveValue : ''
                        field = (
                          <input
                            className={
                              isEnabled
                                ? 'w-full rounded bg-white/10 px-2 py-1 text-white text-xs'
                                : 'w-full rounded bg-white/5 px-2 py-1 text-white/80 text-xs'
                            }
                            type={inferCapabilityInputType(entry.type)}
                            value={value}
                            onChange={(event) => updateValue(event.target.value)}
                          />
                        )
                      }

                      return (
                        <div key={entry.name} className="rounded border border-white/10 bg-white/5 px-3 py-2">
                          <div className="grid grid-cols-1 gap-2 md:grid-cols-[minmax(0,1fr)_minmax(0,360px)] md:items-start">
                            <div className="space-y-1 min-w-0">
                              <div className="flex items-baseline justify-between gap-3">
                                <div className="min-w-0">
                                  <span className="text-white/85 font-medium">{label}</span>
                                </div>
                                <span className="text-[11px] text-white/45">{labelSuffix}</span>
                              </div>
                              {showPath && <div className="text-[11px] text-white/40">{entry.name}</div>}
                              {help && <div className="text-[11px] text-white/50">{help}</div>}
                            </div>
                            <div className={isEnabled ? '' : 'opacity-80'}>
                              <div className="flex items-start gap-2">
                                <div className="flex-1 min-w-0">{field}</div>
                                <label className="inline-flex items-center" title="Override">
                                  <input
                                    type="checkbox"
                                    className="h-3.5 w-3.5 accent-blue-500"
                                    aria-label="Override"
                                    checked={isEnabled}
                                    onChange={(event) => {
                                      const nextEnabled = event.target.checked
                                      setEnabled((prev) => ({ ...prev, [entry.name]: nextEnabled }))
                                      if (!nextEnabled) {
                                        setForm((prev) => ({ ...prev, [entry.name]: baseDisplay }))
                                      }
                                    }}
                                  />
                                </label>
                                <button
                                  type="button"
                                  title="Reset"
                                  className={
                                    isEnabled
                                      ? 'shrink-0 rounded border border-white/10 bg-white/5 px-1.5 py-0.5 text-[10px] text-white/70 hover:bg-white/10'
                                      : 'shrink-0 rounded border border-white/10 bg-white/5 px-1.5 py-0.5 text-[10px] text-white/70 opacity-0 pointer-events-none'
                                  }
                                  onClick={() => {
                                    setEnabled((prev) => ({ ...prev, [entry.name]: false }))
                                    setForm((prev) => ({ ...prev, [entry.name]: baseDisplay }))
                                  }}
                                >
                                  Reset
                                </button>
                              </div>
                            </div>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                ))
            )}
          </div>

          {error && <div className="mb-2 text-[11px] text-red-400">{error}</div>}

          <div className="mt-2 pt-2 border-t border-white/10 flex items-center justify-end gap-2">
            <button
              type="button"
              className="rounded px-2 py-1 text-[11px] text-white/70 hover:bg-white/10"
              onClick={closeAndReset}
            >
              Cancel
            </button>
            <button
              type="button"
              className="rounded bg-sky-500/80 px-3 py-1 text-[11px] text-white hover:bg-sky-500"
              onClick={handleApply}
            >
              Apply
            </button>
          </div>
        </div>
      </div>

      {valueEditorState && (
        <div className="fixed inset-0 z-[110] flex items-center justify-center bg-black/70">
          <div
            role="dialog"
            aria-modal="true"
            className="w-[96vw] max-w-5xl h-[85vh] rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white flex flex-col"
          >
            <div className="flex items-center justify-between gap-3 mb-2">
              <div className="min-w-0">
                <h3 className="text-xs font-semibold text-white/80 truncate">{valueEditorState.label}</h3>
                <div className="text-[11px] text-white/50 font-mono truncate">{valueEditorState.entry.name}</div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className="rounded border border-white/10 bg-white/5 px-2 py-1 text-[11px] text-white/80 hover:bg-white/10"
                  onClick={() => {
                    setValueEditorState(null)
                    setValueEditorError(null)
                  }}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="rounded bg-sky-500/80 px-3 py-1 text-[11px] text-white hover:bg-sky-500"
                  onClick={() => {
                    const { entry, value } = valueEditorState
                    setEnabled((prev) => ({ ...prev, [entry.name]: true }))
                    setForm((prev) => ({ ...prev, [entry.name]: value }))
                    setValueEditorState(null)
                    setValueEditorError(null)
                  }}
                >
                  Apply
                </button>
              </div>
            </div>
            {valueEditorError && <div className="mb-2 text-[11px] text-red-400">{valueEditorError}</div>}
            <div className="flex-1 min-h-0 border border-white/15 rounded overflow-hidden">
              <Editor
                height="100%"
                defaultLanguage={valueEditorState.language}
                theme="vs-dark"
                value={valueEditorState.value}
                onMount={(editor) => {
                  valueEditorRef.current = editor
                }}
                onChange={(value) => {
                  const text = value ?? ''
                  setValueEditorState((prev) => (prev ? { ...prev, value: text } : prev))
                }}
                options={{
                  fontSize: 11,
                  minimap: { enabled: false },
                  scrollBeyondLastLine: false,
                  wordWrap: 'on',
                }}
              />
            </div>
          </div>
        </div>
      )}
    </>
  , document.body)
}

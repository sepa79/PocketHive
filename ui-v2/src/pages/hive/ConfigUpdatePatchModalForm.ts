import {
  formatCapabilityValue,
  type CapabilityConfigEntry,
} from '../../lib/capabilities'

export type ConfigFormValue = string | boolean

export type ConversionResult =
  | { ok: true; apply: boolean; value: unknown }
  | { ok: false; message: string }

function normalizedConfigType(entry: CapabilityConfigEntry): string {
  return entry.type.trim().toLowerCase()
}

export function formatValueForInput(entry: CapabilityConfigEntry, value: unknown): ConfigFormValue {
  const normalizedType = normalizedConfigType(entry)
  if (normalizedType === 'boolean') {
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
  if (normalizedType === 'number' || normalizedType === 'integer') {
    if (typeof value === 'number') return Number.isFinite(value) ? String(value) : ''
    if (typeof value === 'string') return value
    return ''
  }
  return formatCapabilityValue(value)
}

export function convertFormValue(entry: CapabilityConfigEntry, rawValue: ConfigFormValue): ConversionResult {
  const normalizedType = normalizedConfigType(entry)
  if (normalizedType === 'boolean') {
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
  if (normalizedType === 'number' || normalizedType === 'integer') {
    const text = typeof rawValue === 'string' ? rawValue.trim() : ''
    if (!text) return { ok: true, apply: false, value: undefined }
    const parsed = Number(text)
    if (!Number.isFinite(parsed)) {
      return { ok: false, message: `${entry.name} must be a number.` }
    }
    if (normalizedType === 'integer' && !Number.isInteger(parsed)) {
      return { ok: false, message: `${entry.name} must be an integer.` }
    }
    return { ok: true, apply: true, value: parsed }
  }
  if (typeof rawValue === 'string') {
    if (!rawValue.trim()) {
      if (entry.allowBlank === true) {
        return { ok: true, apply: true, value: '' }
      }
      return { ok: true, apply: false, value: undefined }
    }
    return { ok: true, apply: true, value: rawValue }
  }
  return { ok: true, apply: false, value: undefined }
}

export function configInputType(entry: CapabilityConfigEntry): 'number' | 'text' {
  const normalizedType = normalizedConfigType(entry)
  return normalizedType === 'number' || normalizedType === 'integer' ? 'number' : 'text'
}

export function configInputStep(entry: CapabilityConfigEntry): number | undefined {
  const value = entry.ui && typeof entry.ui === 'object'
    ? (entry.ui as Record<string, unknown>).step
    : undefined
  const normalizedType = normalizedConfigType(entry)
  if (typeof value === 'number') {
    if (normalizedType !== 'integer') return value
    return Number.isInteger(value) && value > 0 ? value : 1
  }
  return normalizedType === 'integer' ? 1 : undefined
}

export function isConfigTextarea(entry: CapabilityConfigEntry): boolean {
  return normalizedConfigType(entry) === 'json' || entry.multiline === true
}

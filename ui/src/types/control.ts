export type ControlKind = 'signal' | 'outcome' | 'event' | 'metric'

export interface ControlScope {
  swarmId: string | null
  role: string | null
  instance: string | null
}

export interface ControlEnvelopeBase {
  timestamp: string
  version: string
  kind: ControlKind
  type: string
  origin: string
  scope: ControlScope
  correlationId: string | null
  idempotencyKey: string | null
}

export interface StatusMetricEnvelope extends ControlEnvelopeBase {
  kind: 'metric'
  type: 'status-full' | 'status-delta'
  data: Record<string, unknown>
}

export interface CommandOutcomeEnvelope extends ControlEnvelopeBase {
  kind: 'outcome'
  data?: Record<string, unknown> | null
}

export interface AlertEventEnvelope extends ControlEnvelopeBase {
  kind: 'event'
  type: 'alert'
  data: {
    level: string
    code: string
    message: string
    errorType?: string | null
    errorDetail?: string | null
    logRef?: string | null
    context?: Record<string, unknown> | null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function getString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function isScope(value: unknown): value is ControlScope {
  if (!isRecord(value)) return false
  const swarmId = value['swarmId']
  const role = value['role']
  const instance = value['instance']
  const isNullableString = (v: unknown) => v === null || typeof v === 'string'
  return isNullableString(swarmId) && isNullableString(role) && isNullableString(instance)
}

function isEnvelopeBase(raw: unknown): raw is ControlEnvelopeBase {
  if (!isRecord(raw)) return false
  const kind = getString(raw['kind'])
  const type = getString(raw['type'])
  const origin = getString(raw['origin'])
  const timestamp = getString(raw['timestamp'])
  const version = getString(raw['version'])
  if (!kind || !type || !origin || !timestamp || !version) return false
  if (!isScope(raw['scope'])) return false
  const correlationId = raw['correlationId']
  const idempotencyKey = raw['idempotencyKey']
  const isNullableString = (v: unknown) => v === null || typeof v === 'string'
  if (!isNullableString(correlationId) || !isNullableString(idempotencyKey)) return false
  return true
}

export function isStatusMetricEnvelope(raw: unknown): raw is StatusMetricEnvelope {
  if (!isEnvelopeBase(raw)) return false
  if (raw.kind !== 'metric') return false
  if (raw.type !== 'status-full' && raw.type !== 'status-delta') return false
  return isRecord((raw as unknown as Record<string, unknown>)['data'])
}

export function isCommandOutcomeEnvelope(raw: unknown): raw is CommandOutcomeEnvelope {
  if (!isEnvelopeBase(raw)) return false
  if (raw.kind !== 'outcome') return false
  const data = (raw as unknown as Record<string, unknown>)['data']
  return data === undefined || data === null || isRecord(data)
}

export function isAlertEventEnvelope(raw: unknown): raw is AlertEventEnvelope {
  if (!isEnvelopeBase(raw)) return false
  if (raw.kind !== 'event' || raw.type !== 'alert') return false
  const data = (raw as unknown as Record<string, unknown>)['data']
  if (!isRecord(data)) return false
  const level = getString(data['level'])
  const code = getString(data['code'])
  const message = getString(data['message'])
  return Boolean(level && code && message)
}

export type ControlKind = 'signal' | 'outcome' | 'event' | 'metric'

export interface ControlScope {
  swarmId: string
  role: string
  instance: string
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
  data: Record<string, unknown>
}

export interface StatusMetricEnvelope extends ControlEnvelopeBase {
  kind: 'metric'
  type: 'status-full' | 'status-delta'
}

export interface CommandOutcomeEnvelope extends ControlEnvelopeBase {
  kind: 'outcome'
  correlationId: string
  idempotencyKey: string
  data: Record<string, unknown> & { status: string }
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
  const swarmId = getString(value['swarmId'])
  const role = getString(value['role'])
  const instance = getString(value['instance'])
  return Boolean(swarmId && role && instance)
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
  if (!isRecord(raw['data'])) return false
  const correlationId = raw['correlationId']
  const idempotencyKey = raw['idempotencyKey']
  const isNullableNonEmptyString = (v: unknown) => v === null || getString(v) !== null
  if (!isNullableNonEmptyString(correlationId) || !isNullableNonEmptyString(idempotencyKey)) return false
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
  const record = raw as unknown as Record<string, unknown>
  const data = record['data']
  if (!isRecord(data)) return false
  const status = getString(data['status'])
  const correlationId = getString(record['correlationId'])
  const idempotencyKey = getString(record['idempotencyKey'])
  return Boolean(status && correlationId && idempotencyKey)
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

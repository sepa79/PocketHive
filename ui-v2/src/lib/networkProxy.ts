import { newUuid } from './uuid'

export type NetworkProfile = {
  id: string
  name: string | null
  faults: { type: string; config: Record<string, unknown> }[]
  targets: string[]
}

export type ManualNetworkOverride = {
  enabled: boolean
  latencyMs: number | null
  jitterMs: number | null
  bandwidthKbps: number | null
  slowCloseDelayMs: number | null
  limitDataBytes: number | null
  timeoutMs: number | null
  requestedBy: string
  reason: string | null
  appliedAt: string | null
}

export function createIdempotencyKey() {
  return `ph-${newUuid()}`
}

export async function readErrorMessage(response: Response): Promise<string> {
  try {
    const contentType = response.headers.get('content-type') ?? ''
    if (contentType.includes('application/json')) {
      const payload = (await response.json()) as unknown
      if (payload && typeof payload === 'object' && 'message' in payload) {
        const maybeMessage = (payload as Record<string, unknown>).message
        if (typeof maybeMessage === 'string' && maybeMessage.trim().length > 0) {
          return maybeMessage.trim()
        }
      }
      return JSON.stringify(payload)
    }
    const text = await response.text()
    return text.trim().length > 0 ? text.trim() : `${response.status} ${response.statusText}`
  } catch {
    return `${response.status} ${response.statusText}`
  }
}

export async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new Error(await readErrorMessage(response))
  }
  return (await response.json()) as T
}

export function normalizeProfiles(data: unknown): NetworkProfile[] {
  if (!Array.isArray(data)) return []
  return data
    .map((entry) => {
      if (!entry || typeof entry !== 'object') return null
      const value = entry as Record<string, unknown>
      const id = typeof value.id === 'string' ? value.id.trim() : ''
      if (!id) return null
      const faults = Array.isArray(value.faults)
        ? value.faults
            .map((fault) => {
              if (!fault || typeof fault !== 'object') return null
              const faultValue = fault as Record<string, unknown>
              const type = typeof faultValue.type === 'string' ? faultValue.type.trim() : ''
              if (!type) return null
              const config =
                faultValue.config && typeof faultValue.config === 'object' && !Array.isArray(faultValue.config)
                  ? (faultValue.config as Record<string, unknown>)
                  : {}
              return { type, config }
            })
            .filter((fault): fault is { type: string; config: Record<string, unknown> } => fault !== null)
        : []
      const targets = Array.isArray(value.targets)
        ? value.targets
            .map((target) => (typeof target === 'string' ? target.trim() : ''))
            .filter((target) => target.length > 0)
        : []
      return {
        id,
        name: typeof value.name === 'string' && value.name.trim().length > 0 ? value.name.trim() : null,
        faults,
        targets,
      } satisfies NetworkProfile
    })
    .filter((entry): entry is NetworkProfile => entry !== null)
}

export function formatInstant(value: string | null): string {
  if (!value) return '—'
  const timestamp = Date.parse(value)
  if (Number.isNaN(timestamp)) return value
  return new Date(timestamp).toLocaleString()
}

export function summarizeFaults(profile: NetworkProfile): string {
  if (profile.faults.length === 0) return 'No faults'
  return profile.faults.map((fault) => fault.type).join(', ')
}

export function summarizeFaultConfig(config: Record<string, unknown>): string {
  const entries = Object.entries(config)
  if (entries.length === 0) return 'default'
  return entries
    .map(([key, value]) => `${key}=${String(value)}`)
    .join(', ')
}

export function normalizeManualOverride(data: unknown): ManualNetworkOverride {
  const value = data && typeof data === 'object' ? (data as Record<string, unknown>) : {}
  const integerOrNull = (raw: unknown): number | null =>
    typeof raw === 'number' && Number.isFinite(raw) ? Math.round(raw) : null
  return {
    enabled: value.enabled === true,
    latencyMs: integerOrNull(value.latencyMs),
    jitterMs: integerOrNull(value.jitterMs),
    bandwidthKbps: integerOrNull(value.bandwidthKbps),
    slowCloseDelayMs: integerOrNull(value.slowCloseDelayMs),
    limitDataBytes: integerOrNull(value.limitDataBytes),
    timeoutMs: integerOrNull(value.timeoutMs),
    requestedBy: typeof value.requestedBy === 'string' && value.requestedBy.trim().length > 0 ? value.requestedBy.trim() : 'unknown',
    reason: typeof value.reason === 'string' && value.reason.trim().length > 0 ? value.reason.trim() : null,
    appliedAt: typeof value.appliedAt === 'string' && value.appliedAt.trim().length > 0 ? value.appliedAt.trim() : null,
  }
}

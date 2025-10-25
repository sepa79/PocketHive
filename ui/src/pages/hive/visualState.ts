export type HealthVisualState = 'missing' | 'disabled' | 'ok' | 'warn' | 'alert'

export function mapStatusToVisualState(status: unknown): HealthVisualState | null {
  if (typeof status !== 'string') return null
  const normalized = status.trim().toUpperCase()
  if (!normalized) return null
  if (['OK', 'HEALTHY', 'RUNNING', 'READY', 'STARTING'].includes(normalized)) return 'ok'
  if (['WARN', 'WARNING', 'DEGRADED', 'LATE'].includes(normalized)) return 'warn'
  if (['ALERT', 'ERROR', 'FAILED', 'STOPPED', 'DOWN', 'CRITICAL'].includes(normalized)) return 'alert'
  return null
}

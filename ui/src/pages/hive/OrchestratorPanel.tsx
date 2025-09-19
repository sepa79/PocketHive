import {
  useEffect,
  useMemo,
  useState,
  type KeyboardEvent,
  type MouseEvent,
} from 'react'
import { Play, Square } from 'lucide-react'
import type { Component } from '../../types/hive'
import { heartbeatHealth } from '../../lib/health'
import { sendConfigUpdate } from '../../lib/orchestratorApi'

interface Props {
  orchestrator?: Component | null
  onSelect?: (component: Component) => void
  selectedId?: string
}

type OrchestratorAction = 'start' | 'stop'

type HealthVisualState = 'missing' | 'disabled' | 'ok' | 'warn' | 'alert'

function displayNameFor(orchestrator?: Component | null) {
  if (!orchestrator) return 'Orchestrator'
  const trimmed = typeof orchestrator.name === 'string' ? orchestrator.name.trim() : ''
  if (trimmed) return trimmed
  return orchestrator.id
}

function buttonClasses(isDetected: boolean, variant: OrchestratorAction) {
  const base = [
    'rounded',
    'px-3',
    'py-1',
    'text-sm',
    'font-medium',
    'transition',
    'disabled:cursor-not-allowed',
    'disabled:opacity-60',
  ]

  if (!isDetected) {
    base.push('border border-white/10 bg-white/5 text-white/60')
  } else if (variant === 'start') {
    base.push('border border-emerald-400/40 bg-emerald-400/20 hover:bg-emerald-400/30 text-emerald-100')
  } else {
    base.push('border border-rose-400/40 bg-rose-400/20 hover:bg-rose-400/30 text-rose-100')
  }

  return base.join(' ')
}

export default function OrchestratorPanel({ orchestrator, onSelect, selectedId }: Props) {
  const [heartbeatKey, setHeartbeatKey] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  const [pendingAction, setPendingAction] = useState<OrchestratorAction | null>(null)
  const [updatingEnabled, setUpdatingEnabled] = useState(false)
  const isDetected = Boolean(orchestrator)
  const isSelected = Boolean(orchestrator && selectedId === orchestrator.id)
  const rawConfig = orchestrator?.config as Record<string, unknown> | undefined
  const rawEnabled = rawConfig ? (rawConfig as { enabled?: unknown }).enabled : undefined
  const enabledState: 'true' | 'false' | 'unknown' = !isDetected
    ? 'unknown'
    : typeof rawEnabled === 'boolean'
    ? (rawEnabled ? 'true' : 'false')
    : 'unknown'
  const isEnabled = enabledState === 'true'

  const name = useMemo(() => displayNameFor(orchestrator), [orchestrator])
  const healthState: HealthVisualState = useMemo(() => {
    if (!isDetected || !orchestrator) return 'missing'
    if (enabledState === 'false') return 'disabled'
    const statusDerived = mapStatusToVisualState(orchestrator.status)
    if (statusDerived) return statusDerived
    const health = heartbeatHealth(orchestrator.lastHeartbeat, now)
    switch (health) {
      case 'WARN':
        return 'warn'
      case 'ALERT':
        return 'alert'
      default:
        return 'ok'
    }
  }, [enabledState, isDetected, orchestrator, now])

  const activeSwarmDisplay = useMemo(() => {
    if (!rawConfig) return '—'
    const formatted = extractActiveSwarmCount(rawConfig)
    return formatted ?? '—'
  }, [rawConfig])

  const enabledLabel =
    enabledState === 'true' ? 'Enabled' : enabledState === 'false' ? 'Disabled' : 'Unknown'
  const interactive = Boolean(isDetected && orchestrator && onSelect)
  const cardClasses = [
    'rounded',
    'border',
    'p-3',
    'text-white',
    'transition-colors',
    isDetected ? 'border-white/20 bg-white/10' : 'border-white/10 bg-white/5 opacity-60',
    interactive ? 'cursor-pointer hover:border-emerald-300/40 focus:outline-none focus:ring-2 focus:ring-emerald-300/60' : '',
    isSelected ? 'ring-2 ring-emerald-300/60' : '',
  ]

  const handleSelect = () => {
    if (interactive && orchestrator) {
      onSelect?.(orchestrator)
    }
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!interactive) return
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      handleSelect()
    }
  }

  const actionCopy = pendingAction
    ? {
        label: pendingAction === 'start' ? 'Start all' : 'Stop all',
        verb: pendingAction,
      }
    : null

  useEffect(() => {
    if (!isDetected) return
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [isDetected])

  useEffect(() => {
    if (!orchestrator) {
      setHeartbeatKey(0)
      return
    }
    setHeartbeatKey((key) => key + 1)
    setNow(Date.now())
  }, [orchestrator, orchestrator?.lastHeartbeat, orchestrator?.status])

  const toggleEnabled = async () => {
    if (!isDetected || !orchestrator || updatingEnabled) return
    const next = !isEnabled
    try {
      setUpdatingEnabled(true)
      await sendConfigUpdate(orchestrator, { enabled: next })
    } catch (error) {
      console.error('Failed to update orchestrator config:', error)
    } finally {
      setUpdatingEnabled(false)
    }
  }

  const halTitle = useMemo(
    () => formatLastStatusTooltip(orchestrator, isDetected, now),
    [isDetected, now, orchestrator],
  )

  return (
    <div
      data-testid="orchestrator-panel"
      className={cardClasses.join(' ')}
      data-selected={isSelected ? 'true' : 'false'}
      role={interactive ? 'button' : undefined}
      tabIndex={interactive ? 0 : -1}
      onClick={handleSelect}
      onKeyDown={handleKeyDown}
    >
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="text-xs uppercase tracking-wide text-white/50">Orchestrator</div>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <div className={`text-lg font-semibold ${isDetected ? '' : 'text-white/70'}`}>{name}</div>
          </div>
          <div className="mt-1 flex items-baseline gap-2 text-xs text-white/60">
            <span className="uppercase tracking-wide text-white/40">Active swarms</span>
            <span className="font-semibold text-white/80">{activeSwarmDisplay}</span>
          </div>
        </div>
        <span
          key={heartbeatKey}
          className="hal-eye shrink-0"
          data-state={healthState}
          data-testid="orchestrator-health"
          title={halTitle}
          aria-hidden="true"
        />
      </div>
      <div className="mt-3 flex flex-wrap items-center justify-end gap-2">
        <button
          type="button"
          data-enabled={enabledState}
          data-testid="orchestrator-enabled"
          className="shrink-0"
          disabled={!isDetected || updatingEnabled}
          onClick={(event: MouseEvent<HTMLButtonElement>) => {
            event.stopPropagation()
            toggleEnabled()
          }}
          aria-pressed={isEnabled}
          aria-busy={updatingEnabled}
        >
          {isEnabled ? (
            <Square className="h-3.5 w-3.5" aria-hidden="true" />
          ) : (
            <Play className="h-3.5 w-3.5" aria-hidden="true" />
          )}
          <span>{enabledLabel}</span>
        </button>
        <button
          type="button"
          className={buttonClasses(isDetected, 'start')}
          disabled={!isDetected}
          onClick={(event: MouseEvent<HTMLButtonElement>) => {
            event.stopPropagation()
            setPendingAction('start')
          }}
        >
          Start all
        </button>
        <button
          type="button"
          className={buttonClasses(isDetected, 'stop')}
          disabled={!isDetected}
          onClick={(event: MouseEvent<HTMLButtonElement>) => {
            event.stopPropagation()
            setPendingAction('stop')
          }}
        >
          Stop all
        </button>
      </div>
      {actionCopy && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={(event) => event.stopPropagation()}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="orchestrator-confirm-title"
            className="w-80 rounded border border-white/10 bg-[#1a1d24] p-4 shadow-lg"
            onClick={(event) => event.stopPropagation()}
          >
            <h3 id="orchestrator-confirm-title" className="text-lg font-semibold">
              Confirm {actionCopy.verb} command
            </h3>
            <p className="mt-2 text-sm text-white/70">
              Are you sure you want to send a {actionCopy.verb} command to all swarms via {name}?
            </p>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={(event) => {
                  event.stopPropagation()
                  setPendingAction(null)
                }}
                className="rounded bg-white/10 px-3 py-1 text-sm hover:bg-white/20"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={(event) => {
                  event.stopPropagation()
                  setPendingAction(null)
                }}
                className="rounded bg-white/20 px-3 py-1 text-sm font-medium hover:bg-white/30"
              >
                Send {actionCopy.label}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function mapStatusToVisualState(status: unknown): HealthVisualState | null {
  if (typeof status !== 'string') return null
  const normalized = status.trim().toUpperCase()
  if (!normalized) return null
  if (['OK', 'HEALTHY', 'RUNNING', 'READY', 'STARTING'].includes(normalized)) return 'ok'
  if (['WARN', 'WARNING', 'DEGRADED', 'LATE'].includes(normalized)) return 'warn'
  if (['ALERT', 'ERROR', 'FAILED', 'STOPPED', 'DOWN', 'CRITICAL'].includes(normalized)) return 'alert'
  return null
}

function extractActiveSwarmCount(config: Record<string, unknown>): string | null {
  const candidateKeys = [
    'swarmCount',
    'activeSwarmCount',
    'activeSwarms',
    'swarm-count',
    'active-swarms',
  ]
  for (const key of candidateKeys) {
    if (!(key in config)) continue
    const formatted = formatSwarmCountValue(config[key as keyof typeof config])
    if (formatted !== null) return formatted
  }
  return null
}

function formatSwarmCountValue(value: unknown): string | null {
  if (value == null) return null
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value.toString()
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    return trimmed ? trimmed : null
  }
  if (Array.isArray(value)) {
    return value.length.toString()
  }
  if (typeof value === 'object' && 'count' in (value as Record<string, unknown>)) {
    const countValue = (value as { count?: unknown }).count
    if (typeof countValue === 'number' && Number.isFinite(countValue)) {
      return countValue.toString()
    }
  }
  return null
}

function formatLastStatusTooltip(
  orchestrator: Component | null | undefined,
  isDetected: boolean,
  now: number,
): string {
  if (!isDetected || !orchestrator) {
    return 'Orchestrator missing'
  }

  const statusText =
    typeof orchestrator.status === 'string' && orchestrator.status.trim()
      ? orchestrator.status.trim()
      : 'unknown'

  if (typeof orchestrator.lastHeartbeat === 'number' && Number.isFinite(orchestrator.lastHeartbeat)) {
    const diffSeconds = Math.max(0, Math.floor((now - orchestrator.lastHeartbeat) / 1000))
    return `Last status ${statusText} received ${diffSeconds}s ago`
  }

  return `Last status ${statusText} timing unavailable`
}

import { useEffect, useMemo, useState } from 'react'
import { Play, Square } from 'lucide-react'
import type { Component } from '../../types/hive'
import { heartbeatHealth } from '../../lib/health'

interface Props {
  orchestrator?: Component | null
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

export default function OrchestratorPanel({ orchestrator }: Props) {
  const [heartbeatKey, setHeartbeatKey] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  const [pendingAction, setPendingAction] = useState<OrchestratorAction | null>(null)
  const isDetected = Boolean(orchestrator)
  const rawEnabled = orchestrator?.config
    ? (orchestrator.config as { enabled?: unknown }).enabled
    : undefined
  const enabledState: 'true' | 'false' | 'unknown' = !isDetected
    ? 'unknown'
    : typeof rawEnabled === 'boolean'
    ? (rawEnabled ? 'true' : 'false')
    : 'unknown'

  const name = useMemo(() => displayNameFor(orchestrator), [orchestrator])
  const healthState: HealthVisualState = useMemo(() => {
    if (!isDetected || !orchestrator) return 'missing'
    if (enabledState === 'false') return 'disabled'
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

  const secondsSince = useMemo(() => {
    if (!isDetected || !orchestrator) return null
    if (orchestrator.lastHeartbeat <= 0) return null
    const age = Math.floor((now - orchestrator.lastHeartbeat) / 1000)
    return age < 0 ? 0 : age
  }, [isDetected, orchestrator, now])

  const statusLabel = useMemo(() => {
    if (!isDetected) return 'Not detected'
    if (!orchestrator || orchestrator.lastHeartbeat <= 0) return 'Awaiting status'
    if (secondsSince === null) return 'Status received just now'
    if (secondsSince <= 1) return 'Status received just now'
    const suffix = healthState === 'warn' ? ' (late)' : healthState === 'alert' ? ' (stale)' : ''
    return `Status received ${secondsSince}s ago${suffix}`
  }, [healthState, isDetected, orchestrator, secondsSince])

  const enabledLabel =
    enabledState === 'true' ? 'Enabled' : enabledState === 'false' ? 'Disabled' : 'Unknown'
  const cardClasses = [
    'rounded',
    'border',
    'p-3',
    'text-white',
    'transition-colors',
    isDetected ? 'border-white/20 bg-white/10' : 'border-white/10 bg-white/5 opacity-60',
  ].join(' ')

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
  }, [orchestrator, orchestrator?.lastHeartbeat])

  return (
    <div data-testid="orchestrator-panel" className={cardClasses}>
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="text-xs uppercase tracking-wide text-white/50">Orchestrator</div>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <div className={`text-lg font-semibold ${isDetected ? '' : 'text-white/70'}`}>{name}</div>
            <span
              data-enabled={enabledState}
              data-testid="orchestrator-enabled"
              className="shrink-0"
            >
              {enabledState === 'true' ? (
                <Square className="h-3.5 w-3.5" aria-hidden="true" />
              ) : (
                <Play className="h-3.5 w-3.5" aria-hidden="true" />
              )}
              <span>{enabledLabel}</span>
            </span>
          </div>
          <div className="mt-1 text-xs text-white/60">{statusLabel}</div>
        </div>
        <span
          key={heartbeatKey}
          className="hal-eye shrink-0"
          data-state={healthState}
          data-testid="orchestrator-health"
          title={`Orchestrator status: ${statusLabel}`}
          aria-hidden="true"
        />
      </div>
      <div className="mt-3 flex justify-end gap-2">
        <button
          type="button"
          className={buttonClasses(isDetected, 'start')}
          disabled={!isDetected}
          onClick={() => setPendingAction('start')}
        >
          Start all
        </button>
        <button
          type="button"
          className={buttonClasses(isDetected, 'stop')}
          disabled={!isDetected}
          onClick={() => setPendingAction('stop')}
        >
          Stop all
        </button>
      </div>
      {actionCopy && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="orchestrator-confirm-title"
            className="w-80 rounded border border-white/10 bg-[#1a1d24] p-4 shadow-lg"
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
                onClick={() => setPendingAction(null)}
                className="rounded bg-white/10 px-3 py-1 text-sm hover:bg-white/20"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => setPendingAction(null)}
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

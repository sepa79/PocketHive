import { useMemo, useState } from 'react'
import type { Component } from '../../types/hive'

interface Props {
  orchestrator?: Component | null
}

type OrchestratorAction = 'start' | 'stop'

function displayNameFor(orchestrator?: Component | null) {
  if (!orchestrator) return 'Orchestrator'
  const trimmed = typeof orchestrator.name === 'string' ? orchestrator.name.trim() : ''
  if (trimmed) return trimmed
  return orchestrator.id
}

function halState(isDetected: boolean, isEnabled: boolean) {
  if (!isDetected) return 'down'
  return isEnabled ? 'healthy' : 'unhealthy'
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
  const [pendingAction, setPendingAction] = useState<OrchestratorAction | null>(null)
  const isDetected = Boolean(orchestrator)
  const isEnabled =
    orchestrator?.config &&
    typeof (orchestrator.config as { enabled?: unknown }).enabled === 'boolean'
      ? (orchestrator.config as { enabled?: boolean }).enabled !== false
      : true

  const name = useMemo(() => displayNameFor(orchestrator), [orchestrator])
  const statusLabel = isDetected ? (isEnabled ? 'Active' : 'Disabled') : 'Not detected'
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

  return (
    <div data-testid="orchestrator-panel" className={cardClasses}>
      <div className="flex items-center justify-between gap-3">
        <div>
          <div className="text-xs uppercase tracking-wide text-white/50">Orchestrator</div>
          <div className={`text-lg font-semibold ${isDetected ? '' : 'text-white/70'}`}>{name}</div>
          <div className="text-xs text-white/60">{statusLabel}</div>
        </div>
        <span
          className="hal-eye shrink-0"
          data-state={halState(isDetected, isEnabled)}
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

import { useState, type PropsWithChildren } from 'react'
import styles from './SwarmRow.module.css'
import {
  startSwarm,
  stopSwarm,
  removeSwarm,
} from '../../lib/orchestratorApi'
import { useUIStore } from '../../store'

type SwarmAction = 'start' | 'stop' | 'remove'

export type SwarmRowProps = PropsWithChildren<{
  swarmId: string
  isDefault?: boolean
  isActive?: boolean
  expanded?: boolean
  onActivate?: (swarmId: string) => void
  onRemove?: (swarmId: string) => void
  onToggleExpand?: (swarmId: string) => void
  dataTestId?: string
}>

export default function SwarmRow({
  swarmId,
  isDefault = false,
  isActive = false,
  expanded = false,
  onActivate,
  onRemove,
  onToggleExpand,
  dataTestId,
  children,
}: SwarmRowProps) {
  const [pendingAction, setPendingAction] = useState<SwarmAction | null>(null)
  const setToast = useUIStore((state) => state.setToast)

  const handleActivate = () => {
    if (!isActive) {
      onActivate?.(swarmId)
    }
  }

  const handleToggleExpand = () => {
    onToggleExpand?.(swarmId)
  }

  const rowClassName = expanded
    ? `${styles.row} ${styles.expanded}`
    : styles.row

  const runAction = async (action: SwarmAction) => {
    if (pendingAction) return
    setPendingAction(action)
    const successMessages: Record<SwarmAction, string> = {
      start: `Start command sent for ${swarmId}`,
      stop: `Stop command sent for ${swarmId}`,
      remove: `Remove command sent for ${swarmId}`,
    }
    const errorMessages: Record<SwarmAction, string> = {
      start: `Failed to start swarm ${swarmId}`,
      stop: `Failed to stop swarm ${swarmId}`,
      remove: `Failed to remove swarm ${swarmId}`,
    }

    try {
      if (action === 'start') {
        await startSwarm(swarmId)
      } else if (action === 'stop') {
        await stopSwarm(swarmId)
      } else {
        await removeSwarm(swarmId)
      }
      setToast(successMessages[action])
      if (action === 'remove') {
        onRemove?.(swarmId)
      }
    } catch (error) {
      console.error(error)
      const message =
        error instanceof Error && error.message
          ? `${errorMessages[action]}: ${error.message}`
          : errorMessages[action]
      setToast(message)
    } finally {
      setPendingAction(null)
    }
  }

  const isStarting = pendingAction === 'start'
  const isStopping = pendingAction === 'stop'
  const isRemoving = pendingAction === 'remove'
  const isBusy = pendingAction !== null

  return (
    <div className={rowClassName} data-testid={dataTestId}>
      <div className={styles.header}>
        <div className={styles.left}>
          <button
            type="button"
            aria-label={expanded ? 'Collapse swarm details' : 'Expand swarm details'}
            aria-expanded={expanded}
            className={styles.chevronButton}
            onClick={handleToggleExpand}
          >
            <span className={styles.chevronIcon}>▾</span>
          </button>
          <button
            type="button"
            className={styles.swarmName}
            onClick={handleActivate}
            aria-disabled={isActive}
          >
            {swarmId}
          </button>
        </div>
      </div>
      {expanded && (
        <div className={styles.content}>
          {children}
          <div className={styles.controls} role="group" aria-label="Swarm controls">
            <button
              type="button"
              className={`${styles.controlButton} ${styles.startButton}`}
              onClick={() => runAction('start')}
              disabled={isBusy}
              aria-busy={isStarting}
            >
              {isStarting ? 'Starting…' : 'Start swarm'}
            </button>
            <button
              type="button"
              className={`${styles.controlButton} ${styles.stopButton}`}
              onClick={() => runAction('stop')}
              disabled={isBusy}
              aria-busy={isStopping}
            >
              {isStopping ? 'Stopping…' : 'Stop swarm'}
            </button>
            <button
              type="button"
              className={`${styles.controlButton} ${styles.removeButton}`}
              onClick={() => runAction('remove')}
              disabled={isBusy || isDefault}
              aria-busy={isRemoving}
              title={isDefault ? 'Default swarm cannot be removed' : undefined}
            >
              {isRemoving ? 'Removing…' : 'Remove swarm'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

import {
  useState,
  type PropsWithChildren,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
} from 'react'
import styles from './SwarmRow.module.css'
import {
  startSwarm,
  stopSwarm,
  removeSwarm,
} from '../../lib/orchestratorApi'
import { useUIStore } from '../../store'
import { type HealthVisualState } from '../../pages/hive/visualState'
import { Play, Square, ZoomIn } from 'lucide-react'

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
  healthState?: HealthVisualState
  healthTitle?: string
  statusKey?: number
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
  healthState = 'missing',
  healthTitle = 'Swarm status unavailable',
  statusKey = 0,
  children,
}: SwarmRowProps) {
  const [pendingAction, setPendingAction] = useState<SwarmAction | null>(null)
  const setToast = useUIStore((state) => state.setToast)

  const interactive = Boolean(onActivate)

  const handleActivate = () => {
    if (!interactive) return
    if (!isActive) {
      onActivate?.(swarmId)
    }
  }

  const handleRowClick = () => {
    handleActivate()
  }

  const handleKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (!interactive) return
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      handleActivate()
    }
  }

  const handleToggleExpand = (event: ReactMouseEvent<HTMLButtonElement>) => {
    event.stopPropagation()
    onToggleExpand?.(swarmId)
  }

  const rowClassName = [styles.row, expanded ? styles.expanded : '']
    .filter(Boolean)
    .join(' ')

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
    <div
      className={rowClassName}
      data-testid={dataTestId}
      data-active={isActive ? 'true' : 'false'}
      data-expanded={expanded ? 'true' : 'false'}
      data-interactive={interactive ? 'true' : 'false'}
      onClick={handleRowClick}
      {...(interactive
        ? {
            role: 'button' as const,
            tabIndex: 0,
            onKeyDown: handleKeyDown,
            'aria-pressed': isActive ? 'true' : 'false',
          }
        : {})}
    >
      <div className={styles.header}>
        <div className={styles.meta}>
          <button
            type="button"
            aria-label={expanded ? 'Collapse swarm details' : 'Expand swarm details'}
            aria-expanded={expanded}
            className={styles.chevronButton}
            onClick={handleToggleExpand}
          >
            <span className={styles.chevronIcon}>▾</span>
          </button>
          <span className={styles.swarmName}>{swarmId}</span>
        </div>
        <div className={styles.utility}>
          <div className={styles.actions} role="group" aria-label="Swarm controls">
            <button
              type="button"
              className={`${styles.iconButton} ${styles.zoomButton}`}
              onClick={(event) => {
                event.stopPropagation()
                handleActivate()
              }}
              aria-pressed={isActive ? 'true' : 'false'}
              title={isActive ? 'Viewing this swarm' : 'Focus on this swarm'}
              disabled={!interactive}
            >
              <ZoomIn size={16} aria-hidden="true" />
              <span className={styles.srOnly}>Focus swarm</span>
            </button>
            <button
              type="button"
              className={`${styles.iconButton} ${styles.startButton}`}
              onClick={(event) => {
                event.stopPropagation()
                runAction('start')
              }}
              disabled={isBusy}
              aria-busy={isStarting}
              title={isStarting ? 'Start command in progress' : 'Start swarm'}
              data-pending={isStarting ? 'true' : 'false'}
            >
              <Play size={16} aria-hidden="true" />
              <span className={styles.srOnly}>Start swarm</span>
            </button>
            <button
              type="button"
              className={`${styles.iconButton} ${styles.stopButton}`}
              onClick={(event) => {
                event.stopPropagation()
                runAction('stop')
              }}
              disabled={isBusy}
              aria-busy={isStopping}
              title={isStopping ? 'Stop command in progress' : 'Stop swarm'}
              data-pending={isStopping ? 'true' : 'false'}
            >
              <Square size={16} aria-hidden="true" />
              <span className={styles.srOnly}>Stop swarm</span>
            </button>
          </div>
          <span className={styles.status} title={healthTitle} aria-label={healthTitle} role="img">
            <span key={statusKey} className="hal-eye" data-state={healthState} aria-hidden="true" />
          </span>
        </div>
      </div>
      {expanded && (
        <div
          className={styles.content}
          onClick={(event) => event.stopPropagation()}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.stopPropagation()
            }
          }}
        >
          {children}
          <div className={styles.controls}>
            <button
              type="button"
              className={`${styles.controlButton} ${styles.removeButton}`}
              onClick={(event) => {
                event.stopPropagation()
                runAction('remove')
              }}
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

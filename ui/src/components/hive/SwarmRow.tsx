import {
  useMemo,
  useState,
  type PropsWithChildren,
  type MouseEvent as ReactMouseEvent,
  type KeyboardEvent,
} from 'react'
import styles from './SwarmRow.module.css'
import {
  startSwarm,
  stopSwarm,
  removeSwarm,
} from '../../lib/orchestratorApi'
import { useUIStore } from '../../store'
import { type HealthVisualState } from '../../pages/hive/visualState'
import { Play, Square, ZoomIn, ZoomOut } from 'lucide-react'

type SwarmAction = 'start' | 'stop' | 'remove'

export type SwarmRowProps = PropsWithChildren<{
  swarmId: string
  isDefault?: boolean
  isActive?: boolean
  expanded?: boolean
  isSelected?: boolean
  componentCount?: number
  onFocusChange?: (swarmId: string, nextActive: boolean) => void
  onSelect?: (swarmId: string) => void
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
  isSelected = false,
  componentCount = 0,
  onRemove,
  onToggleExpand,
  dataTestId,
  healthState = 'missing',
  healthTitle = 'Swarm status unavailable',
  statusKey = 0,
  children,
  onFocusChange,
  onSelect,
}: SwarmRowProps) {
  const [pendingAction, setPendingAction] = useState<SwarmAction | null>(null)
  const setToast = useUIStore((state) => state.setToast)

  const interactive = Boolean(onSelect)
  const normalizedComponentCount = Number.isFinite(componentCount)
    ? Math.max(0, Math.floor(componentCount))
    : 0
  const componentLabel = normalizedComponentCount === 1 ? '1 component' : `${normalizedComponentCount} components`
  const sanitizedId = useMemo(() => normalizeForId(`${swarmId}-content`), [swarmId])

  const handleToggleExpand = (event: ReactMouseEvent<HTMLButtonElement>) => {
    event.stopPropagation()
    onToggleExpand?.(swarmId)
  }

  const handleFocusToggle = (event: ReactMouseEvent<HTMLButtonElement>) => {
    event.stopPropagation()
    if (!onFocusChange) return
    onFocusChange(swarmId, !isActive)
  }

  const handleSelect = () => {
    onSelect?.(swarmId)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!interactive) return
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      handleSelect()
    }
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
  const rowLabel = interactive ? `Select swarm ${swarmId}` : undefined

  return (
    <div
      className={rowClassName}
      data-testid={dataTestId}
      data-active={isActive ? 'true' : 'false'}
      data-expanded={expanded ? 'true' : 'false'}
      data-selected={isSelected ? 'true' : 'false'}
      data-interactive={interactive ? 'true' : 'false'}
      role={interactive ? 'button' : undefined}
      tabIndex={interactive ? 0 : undefined}
      aria-pressed={interactive ? (isSelected ? 'true' : 'false') : undefined}
      aria-label={rowLabel}
      onClick={interactive ? handleSelect : undefined}
      onKeyDown={handleKeyDown}
    >
      <div className={styles.header}>
        <div className={styles.meta}>
          <button
            type="button"
            aria-label={expanded ? 'Collapse swarm details' : 'Expand swarm details'}
            aria-expanded={expanded}
            className={styles.chevronButton}
            onClick={handleToggleExpand}
            aria-controls={sanitizedId}
          >
            <span className={styles.chevronIcon}>▾</span>
          </button>
          <div className={styles.metaColumn}>
            <span className={styles.swarmName}>{swarmId}</span>
            <div className={styles.badgeRow}>
              {isDefault ? (
                <span className={`${styles.badge} ${styles.defaultBadge}`}>Default</span>
              ) : null}
              <span className={`${styles.badge} ${styles.countBadge}`} data-testid="swarm-component-count">
                {componentLabel}
              </span>
            </div>
          </div>
        </div>
        <div className={styles.utility}>
          <div className={styles.actions} role="group" aria-label="Swarm controls">
            <button
              type="button"
              className={`${styles.iconButton} ${styles.zoomButton}`}
              onClick={handleFocusToggle}
              aria-pressed={isActive ? 'true' : 'false'}
              title={
                isActive ? 'Exit focused view for this swarm' : 'Focus on this swarm'
              }
              disabled={!onFocusChange}
            >
              {isActive ? (
                <ZoomOut size={16} aria-hidden="true" />
              ) : (
                <ZoomIn size={16} aria-hidden="true" />
              )}
              <span className={styles.srOnly}>
                {isActive ? 'Exit focused swarm view' : 'Focus swarm'}
              </span>
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
          <span
            key={statusKey}
            className={`hal-eye ${styles.statusEye}`}
            data-state={healthState}
            title={healthTitle}
            aria-hidden="true"
          />
        </div>
      </div>
      {expanded && (
        <div
          className={styles.content}
          id={sanitizedId}
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

function normalizeForId(value: string) {
  return value.replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '') || 'swarm-content'
}

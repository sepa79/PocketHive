import type { PropsWithChildren } from 'react'
import styles from './SwarmRow.module.css'

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

  return (
    <div className={rowClassName} data-testid={dataTestId}>
      <div className={styles.header}>
        <div className={styles.left}>
          <button
            type="button"
            aria-label={expanded ? 'Collapse swarm details' : 'Expand swarm details'}
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
        {!isDefault && (
          <button
            type="button"
            className={styles.removeButton}
            onClick={() => onRemove?.(swarmId)}
          >
            Remove swarm
          </button>
        )}
      </div>
      <div className={styles.content}>
        {children}
        {expanded && (
          <div className={styles.dropdown}>Swarm controls coming soon.</div>
        )}
      </div>
    </div>
  )
}

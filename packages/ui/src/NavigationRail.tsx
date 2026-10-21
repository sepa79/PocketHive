import { useId, type ReactNode } from 'react'
import styles from './NavigationRail.module.css'

type NavigationRailProps = {
  expanded: boolean
  onExpandedChange: (expanded: boolean) => void
  label: string
  expandLabel: string
  collapseLabel: string
  children: ReactNode
}

/**
 * Responsibility: present the controlled navigation rail and request expand/collapse.
 * Must not: persist preferences, select app menus or infer navigation permissions.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export function NavigationRail({ expanded, onExpandedChange, label, expandLabel, collapseLabel, children }: NavigationRailProps) {
  const itemsId = useId()
  const toggleLabel = expanded ? collapseLabel : expandLabel

  return (
    <aside className={styles.rail}>
      <nav className={styles.stack} aria-label={label}>
        <div className={styles.heading} data-expanded={expanded}>
          <button
            type="button"
            className={styles.toggle}
            onClick={() => onExpandedChange(!expanded)}
            title={toggleLabel}
            aria-label={toggleLabel}
            aria-expanded={expanded}
            aria-controls={itemsId}
          >
            <span aria-hidden="true">{expanded ? '«' : '»'}</span>
          </button>
          {expanded ? <div className={styles.title}>{label}</div> : null}
        </div>
        <div id={itemsId} className={styles.items}>{children}</div>
      </nav>
    </aside>
  )
}

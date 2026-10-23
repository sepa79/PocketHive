import type { ReactNode } from 'react'
import type { NavigationItemPresentation } from './NavigationItemPresentation'
import styles from './NavigationItem.module.css'

type NavigationItemProps = {
  label: string
  icon: ReactNode
  expanded: boolean
  children: (presentation: NavigationItemPresentation) => ReactNode
}

/**
 * Responsibility: supply shared navigation-item presentation to a host link renderer.
 * Must not: select routes, determine access or maintain an independent active route.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export function NavigationItem({ label, icon, expanded, children }: NavigationItemProps) {
  return children({
    className: expanded ? styles.expanded : styles.collapsed,
    title: label,
    'aria-label': label,
    children: <>{icon}{expanded ? <span className={styles.label}>{label}</span> : null}</>,
  })
}

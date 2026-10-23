import type { ReactNode } from 'react'
import styles from './AppHeader.module.css'

type AppHeaderProps = {
  leading: ReactNode
  center: ReactNode
  trailing: ReactNode
}

/**
 * Responsibility: present application-provided header regions in the shared shell.
 * Must not: supply branding, account controls, notifications or authentication.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export function AppHeader({ leading, center, trailing }: AppHeaderProps) {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <div className={styles.leading}>{leading}</div>
        <div className={styles.center}>{center}</div>
        <div className={styles.trailing}>{trailing}</div>
      </div>
    </header>
  )
}

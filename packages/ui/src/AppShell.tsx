import type { ReactNode } from 'react'
import styles from './AppShell.module.css'

type AppShellProps = {
  navigationExpanded: boolean
  header: ReactNode
  navigation: ReactNode
  toolbar: ReactNode
  children: ReactNode
}

/**
 * Responsibility: arrange application-supplied shell regions and content scrolling.
 * Must not: own navigation preferences, routes, auth, permissions or domain state.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export function AppShell({ navigationExpanded, header, navigation, toolbar, children }: AppShellProps) {
  return (
    <div className={styles.shell} data-navigation={navigationExpanded ? 'expanded' : 'collapsed'}>
      {header}
      {navigation}
      <main className={styles.main}>
        {toolbar}
        <div className={styles.content}>{children}</div>
      </main>
    </div>
  )
}

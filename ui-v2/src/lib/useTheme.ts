import { useSyncExternalStore } from 'react'
import { getTheme, type Theme } from './theme'

function subscribe(onChange: () => void) {
  const observer = new MutationObserver(onChange)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  return () => observer.disconnect()
}

/**
 * Responsibility: project the applied PH theme into React subscriptions.
 * Must not: select, persist or mutate the application theme.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, getTheme)
}

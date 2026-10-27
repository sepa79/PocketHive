import { useTheme } from './useTheme'

/**
 * Responsibility: map the applied PH theme to the matching Monaco preset.
 * Must not: observe a separate theme source or change application preferences.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export function useMonacoTheme() {
  return useTheme() === 'light' ? 'vs' : 'vs-dark'
}

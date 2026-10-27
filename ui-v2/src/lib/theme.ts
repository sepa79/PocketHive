/**
 * Responsibility: own PH theme bootstrap, applied root state and persisted preference.
 * Must not: style widgets or maintain React component state.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export type Theme = 'dark' | 'light'

const STORAGE_KEY = 'PH_UIV2_THEME'

function readStoredTheme(): Theme {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    return raw === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

export function getTheme(): Theme {
  const theme = document.documentElement.dataset.theme
  if (theme !== 'dark' && theme !== 'light') {
    throw new Error('PocketHive theme must be installed before rendering the application.')
  }
  return theme
}

export function setTheme(theme: Theme) {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // ignore
  }
  try {
    document.documentElement.dataset.theme = theme
  } catch {
    // ignore
  }
}

export function installTheme() {
  try {
    document.documentElement.dataset.theme = readStoredTheme()
  } catch {
    // ignore
  }
}

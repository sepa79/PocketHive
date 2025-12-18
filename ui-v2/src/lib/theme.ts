export type Theme = 'dark' | 'light'

const STORAGE_KEY = 'PH_UIV2_THEME'

export function getTheme(): Theme {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    return raw === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
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
    document.documentElement.dataset.theme = getTheme()
  } catch {
    // ignore
  }
}

import { create } from 'zustand'

interface UiState {
  sidebarOpen: boolean
  toggleSidebar: () => void
  theme: 'dark' | 'light'
  toggleTheme: () => void
}

export const useUiStore = create<UiState>((set) => ({
  sidebarOpen: true,
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
  theme: 'dark',
  toggleTheme: () =>
    set((s) => {
      const next = s.theme === 'dark' ? 'light' : 'dark'
      const html = document.documentElement
      if (next === 'dark') html.classList.add('dark')
      else html.classList.remove('dark')
      return { theme: next }
    })
}))

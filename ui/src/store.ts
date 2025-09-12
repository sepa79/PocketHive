import { create } from 'zustand'

interface UIState {
  sidebarOpen: boolean
  toggleSidebar: () => void
  closeSidebar: () => void
  messageLimit: number
  setMessageLimit: (limit: number) => void
  debugMode: boolean
  toggleDebug: () => void
  toast: string | null
  setToast: (msg: string) => void
  clearToast: () => void
  buzzVisible: boolean
  toggleBuzz: () => void
  buzzDock: 'left' | 'right' | 'bottom'
  setBuzzDock: (pos: 'left' | 'right' | 'bottom') => void
  buzzSize: number
  setBuzzSize: (size: number) => void
}

export const useUIStore = create<UIState>((set) => ({
  sidebarOpen: false,
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
  closeSidebar: () => set({ sidebarOpen: false }),
  messageLimit: 100,
  setMessageLimit: (limit: number) => set({ messageLimit: Math.max(10, Math.min(500, limit)) }),
  debugMode: false,
  toggleDebug: () => set((s) => ({ debugMode: !s.debugMode })),
  toast: null,
  setToast: (msg: string) => set({ toast: msg }),
  clearToast: () => set({ toast: null }),
  buzzVisible: false,
  toggleBuzz: () => set((s) => ({ buzzVisible: !s.buzzVisible })),
  buzzDock: 'right',
  setBuzzDock: (pos) => set({ buzzDock: pos }),
  buzzSize: 30,
  setBuzzSize: (size: number) => set({ buzzSize: Math.max(10, Math.min(90, size)) }),
}))

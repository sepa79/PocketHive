import { create } from 'zustand'
import type { StoreApi, UseBoundStore } from 'zustand'

interface UIState {
  sidebarOpen: boolean
  toggleSidebar: () => void
  closeSidebar: () => void
  messageLimit: number
  setMessageLimit: (limit: number) => void
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

type UIStore = UseBoundStore<StoreApi<UIState>>

const globalObject = globalThis as typeof globalThis & {
  __POCKETHIVE_UI_STORE__?: UIStore
}

const createUIStore = () =>
  create<UIState>((set) => ({
    sidebarOpen: false,
    toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
    closeSidebar: () => set({ sidebarOpen: false }),
    messageLimit: 100,
    setMessageLimit: (limit: number) => set({ messageLimit: Math.max(10, Math.min(500, limit)) }),
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

export const useUIStore: UIStore =
  globalObject.__POCKETHIVE_UI_STORE__ ?? (globalObject.__POCKETHIVE_UI_STORE__ = createUIStore())

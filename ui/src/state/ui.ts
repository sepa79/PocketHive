import { create } from 'zustand';

interface UIState {
  sidebarOpen: boolean;
  dark: boolean;
  toggleSidebar: () => void;
  toggleTheme: () => void;
}

export const useUIState = create<UIState>((set) => ({
  sidebarOpen: false,
  dark: true,
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
  toggleTheme: () => set((s) => ({ dark: !s.dark })),
}));

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

type TopBarContextValue = {
  toolbar: ReactNode | null
  setToolbar: (toolbar: ReactNode | null) => void
}

const TopBarContext = createContext<TopBarContextValue | null>(null)

export function TopBarProvider({ children }: { children: ReactNode }) {
  const [toolbar, setToolbar] = useState<ReactNode | null>(null)
  const value = useMemo(() => ({ toolbar, setToolbar }), [toolbar])
  return <TopBarContext.Provider value={value}>{children}</TopBarContext.Provider>
}

export function useTopBarToolbar(toolbar: ReactNode | null) {
  const context = useContext(TopBarContext)
  useEffect(() => {
    if (!context) return
    context.setToolbar(toolbar)
    return () => context.setToolbar(null)
  }, [context, toolbar])
}

export function useTopBarContext() {
  return useContext(TopBarContext)
}

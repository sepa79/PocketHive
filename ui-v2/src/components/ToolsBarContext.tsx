import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

type ToolsBarContextValue = {
  tools: ReactNode | null
  setTools: (tools: ReactNode | null) => void
}

const ToolsBarContext = createContext<ToolsBarContextValue | null>(null)

export function ToolsBarProvider({ children }: { children: ReactNode }) {
  const [tools, setTools] = useState<ReactNode | null>(null)
  const value = useMemo(() => ({ tools, setTools }), [tools])
  return <ToolsBarContext.Provider value={value}>{children}</ToolsBarContext.Provider>
}

export function useToolsBar(tools: ReactNode | null) {
  const context = useContext(ToolsBarContext)
  useEffect(() => {
    if (!context) return
    context.setTools(tools)
    return () => context.setTools(null)
  }, [context, tools])
}

export function useToolsBarContext() {
  return useContext(ToolsBarContext)
}

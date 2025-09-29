import { ReactNode } from 'react'
import { Root, createRoot } from 'react-dom/client'
import ShellProviders from '../ShellProviders'
import type { Theme } from '../lib/theme'

export type ShellRootOptions = {
  withRouter?: boolean
  theme?: Theme
}

export type ShellRoot = {
  render: (node: ReactNode) => void
  unmount: () => void
}

export function createShellRoot(container: HTMLElement, options?: ShellRootOptions): ShellRoot {
  const root: Root = createRoot(container)
  let isMounted = false

  return {
    render(node: ReactNode) {
      isMounted = true
      root.render(
        <ShellProviders withRouter={options?.withRouter ?? false} theme={options?.theme}>
          {node}
        </ShellProviders>,
      )
    },
    unmount() {
      if (!isMounted) {
        return
      }
      root.unmount()
      isMounted = false
    },
  }
}

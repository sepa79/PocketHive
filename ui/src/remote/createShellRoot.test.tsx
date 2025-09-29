import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createShellRoot, resetConfig, setConfig, useConfig, useUIStore } from '../shared'

declare global {
  // eslint-disable-next-line no-var
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true

function RemoteConfigProbe() {
  const cfg = useConfig()
  return <span data-testid="prometheus-endpoint">{cfg.prometheus}</span>
}

function RemoteStoreProbe() {
  const { sidebarOpen, toggleSidebar } = useUIStore()
  return (
    <div>
      <span data-testid="sidebar-open">{sidebarOpen ? 'open' : 'closed'}</span>
      <button type="button" data-testid="sidebar-toggle" onClick={toggleSidebar}>
        Toggle
      </button>
    </div>
  )
}

describe('createShellRoot', () => {
  beforeEach(() => {
    resetConfig()
    useUIStore.setState({ sidebarOpen: false })
  })

  afterEach(() => {
    useUIStore.setState({ sidebarOpen: false })
  })

  it('provides host configuration to remote components', () => {
    const container = document.createElement('div')
    document.body.appendChild(container)

    const shellRoot = createShellRoot(container)

    setConfig({ prometheus: 'https://prometheus.internal/' })

    act(() => {
      shellRoot.render(<RemoteConfigProbe />)
    })

    const probe = container.querySelector('[data-testid="prometheus-endpoint"]')
    expect(probe?.textContent).toBe('https://prometheus.internal/')

    act(() => shellRoot.unmount())
    container.remove()
  })

  it('shares zustand store instances with remotes', () => {
    const container = document.createElement('div')
    document.body.appendChild(container)

    const shellRoot = createShellRoot(container)

    act(() => {
      shellRoot.render(<RemoteStoreProbe />)
    })

    const sidebarState = () => container.querySelector('[data-testid="sidebar-open"]')?.textContent
    const toggleButton = container.querySelector('[data-testid="sidebar-toggle"]') as HTMLButtonElement

    expect(sidebarState()).toBe('closed')

    act(() => {
      toggleButton.click()
    })

    expect(sidebarState()).toBe('open')

    act(() => shellRoot.unmount())
    container.remove()
  })
})

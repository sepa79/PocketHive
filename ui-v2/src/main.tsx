import { StrictMode } from 'react'
import type { ReactElement } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, MemoryRouter } from 'react-router-dom'
import App from './App'
import './styles.css'
import { installTheme } from './lib/theme'
import { bootstrapControlPlane } from './lib/controlPlane/bootstrap'
import { startControlPlaneHealth } from './lib/controlPlane/healthStore'
import { detectUiBasename } from './lib/routing/basename'
import { AuthProvider } from './lib/authContext'
import { installAuthenticatedFetch } from './lib/auth'

declare const __PLUGIN_MODE__: boolean

installTheme()
installAuthenticatedFetch()

const isPlugin = typeof __PLUGIN_MODE__ !== 'undefined' && __PLUGIN_MODE__

function renderApp(children: ReactElement) {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <AuthProvider>{children}</AuthProvider>
    </StrictMode>,
  )
}

if (isPlugin) {
  window.addEventListener('message', function onConfig(event: MessageEvent) {
    const msg = event.data
    if (!msg || msg.type !== 'config') return
    window.removeEventListener('message', onConfig)

    const { route = '/' } = msg.payload ?? {}
    ;(window as any).__phPluginConfig = msg.payload

    bootstrapControlPlane()
    startControlPlaneHealth()

    renderApp(
      <MemoryRouter initialEntries={[route]}>
        <App />
      </MemoryRouter>,
    )
  })
} else {
  bootstrapControlPlane()
  startControlPlaneHealth()

  renderApp(
    <BrowserRouter basename={detectUiBasename(window.location.pathname) || undefined}>
      <App />
    </BrowserRouter>,
  )
}

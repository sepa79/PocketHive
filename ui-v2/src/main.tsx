import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, MemoryRouter } from 'react-router-dom'
import App from './App'
import './styles.css'
import { installTheme } from './lib/theme'
import { bootstrapControlPlane } from './lib/controlPlane/bootstrap'
import { startControlPlaneHealth } from './lib/controlPlane/healthStore'
import { detectUiBasename } from './lib/routing/basename'

declare const __PLUGIN_MODE__: boolean;

installTheme()

// In plugin mode the extension host sends a 'config' postMessage before the
// app renders. We wait for it so we can inject baseUrl and the initial route.
const isPlugin = typeof __PLUGIN_MODE__ !== 'undefined' && __PLUGIN_MODE__;

if (isPlugin) {
  // Plugin mode: wait for config message, then mount with MemoryRouter
  window.addEventListener('message', function onConfig(event: MessageEvent) {
    const msg = event.data;
    if (!msg || msg.type !== 'config') return;
    window.removeEventListener('message', onConfig);

    const { route = '/' } = msg.payload ?? {};

    // Store config globally so components can read baseUrl etc.
    (window as any).__phPluginConfig = msg.payload;

    bootstrapControlPlane()
    startControlPlaneHealth()

    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <MemoryRouter initialEntries={[route]}>
          <App />
        </MemoryRouter>
      </StrictMode>,
    )
  })
} else {
  // Normal browser mode
  bootstrapControlPlane()
  startControlPlaneHealth()

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <BrowserRouter basename={detectUiBasename(window.location.pathname) || undefined}>
        <App />
      </BrowserRouter>
    </StrictMode>,
  )
}

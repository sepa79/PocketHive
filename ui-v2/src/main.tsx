import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './styles.css'
import { installTheme } from './lib/theme'
import { bootstrapControlPlane } from './lib/controlPlane/bootstrap'
import { startControlPlaneHealth } from './lib/controlPlane/healthStore'
import { CONTROL_PLANE_STOMP_URL } from './lib/controlPlane/config'

installTheme()
bootstrapControlPlane()
startControlPlaneHealth(CONTROL_PLANE_STOMP_URL)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter basename="/v2">
      <App />
    </BrowserRouter>
  </StrictMode>,
)

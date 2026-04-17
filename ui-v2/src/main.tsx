import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './styles.css'
import { installTheme } from './lib/theme'
import { bootstrapControlPlane } from './lib/controlPlane/bootstrap'
import { startControlPlaneHealth } from './lib/controlPlane/healthStore'
import { detectUiBasename } from './lib/routing/basename'
import { AuthProvider } from './lib/authContext'
import { installAuthenticatedFetch } from './lib/auth'

installTheme()
installAuthenticatedFetch()
bootstrapControlPlane()
startControlPlaneHealth()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <BrowserRouter basename={detectUiBasename(window.location.pathname) || undefined}>
        <App />
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
)

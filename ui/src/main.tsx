import { createRoot } from 'react-dom/client'
import './styles/globals.css'
import App from './App.tsx'
import ShellProviders from './ShellProviders'

createRoot(document.getElementById('root')!).render(
  <ShellProviders>
    <App />
  </ShellProviders>,
)

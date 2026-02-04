import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  // Use relative asset paths so the UI can be hosted behind any proxy base-path
  // (e.g. `/`, `/v2`, `/ph/ui`) without rebuilding.
  base: './',
  plugins: [react()],
})

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  publicDir: 'assets',
  resolve: {
    alias: {
      '@spec': fileURLToPath(new URL('../spec', import.meta.url))
    }
  }
})

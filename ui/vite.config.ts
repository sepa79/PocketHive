import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  plugins: [react() as any],
  publicDir: 'assets',
  test: {
    environment: 'jsdom'
  }
})

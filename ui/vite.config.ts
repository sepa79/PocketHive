/// <reference types="vitest" />
import { defineConfig, type UserConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  publicDir: 'assets',
  test: {
    environment: 'node',
  },
} as unknown as UserConfig)

import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import federation from '@originjs/vite-plugin-federation'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const isScenario = mode === 'scenario'

  return {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    plugins: [
      react() as any,
      federation({
        name: '@ph/scenario',
        filename: 'remoteEntry.js',
        exposes: {
          './ScenarioApp': './src/scenario/remoteEntry.ts'
        },
        shared: ['react', 'react-dom']
      })
    ],
    publicDir: 'assets',
    build: {
      target: 'esnext',
      modulePreload: false,
      outDir: isScenario ? 'dist/scenario' : 'dist'
    },
    ...(isScenario
      ? {
          optimizeDeps: {
            entries: ['src/scenario/remoteEntry.ts']
          }
        }
      : {}),
    test: {
      environment: 'jsdom'
    }
  }
})

import { fileURLToPath, URL } from 'node:url'
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
        shared: {
          react: { singleton: true, eager: true, requiredVersion: '^19.1.1' },
          'react-dom': { singleton: true, eager: true, requiredVersion: '^19.1.1' },
          zustand: { singleton: true, requiredVersion: '^5.0.8' },
          '@ph/shell': { import: './src/shell/index.ts', singleton: true },
          './src/lib/config': { import: './src/lib/config', singleton: true },
          './src/store': { import: './src/store', singleton: true }
        }
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
    },
    resolve: {
      alias: {
        '@ph/shell': fileURLToPath(new URL('./src/shell/index.ts', import.meta.url))
      }
    }
  }
})

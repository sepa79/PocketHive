import { defineConfig, mergeConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'
import viteConfig from './vite.config.ts'

export default mergeConfig(viteConfig, defineConfig({
  server: {
    fs: {
      allow: [
        fileURLToPath(new URL('.', import.meta.url)),
        fileURLToPath(new URL('../scenario-manager-service/capabilities', import.meta.url)),
      ],
    },
  },
  test: {
    projects: [
      {
        extends: true,
        test: {
          name: 'components',
          environment: 'jsdom',
          include: ['src/**/*.{test,spec}.tsx'],
          restoreMocks: true,
        },
      },
      {
        extends: true,
        test: {
          name: 'units',
          environment: 'node',
          include: ['src/**/*.{test,spec}.ts'],
        },
      },
    ],
  },
}))

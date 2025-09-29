import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import federation from '@originjs/vite-plugin-federation'
import pkg from './package.json' assert { type: 'json' }

const { dependencies } = pkg as { dependencies: Record<string, string> }

// https://vite.dev/config/
export default defineConfig({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  plugins: [
    react() as any,
    federation({
      name: 'shell',
      filename: 'remoteEntry.js',
      exposes: {
        './ShellProviders': './src/ShellProviders.tsx',
        './shared': './src/shared/index.ts',
        './createShellRoot': './src/remote/createShellRoot.tsx',
      },
      shared: {
        react: { singleton: true, eager: true, requiredVersion: dependencies.react },
        'react-dom': {
          singleton: true,
          eager: true,
          requiredVersion: dependencies['react-dom'],
        },
        'react-router-dom': {
          singleton: true,
          requiredVersion: dependencies['react-router-dom'],
        },
        '@tanstack/react-query': {
          singleton: true,
          requiredVersion: dependencies['@tanstack/react-query'],
        },
        zustand: {
          singleton: true,
          requiredVersion: dependencies.zustand,
        },
        './lib/config': {
          import: './src/lib/config.tsx',
          singleton: true,
        },
        './store': {
          import: './src/store.ts',
          singleton: true,
        },
      },
    }) as any,
  ],
  publicDir: 'assets',
  test: {
    environment: 'jsdom'
  },
  build: {
    target: 'esnext',
    modulePreload: false,
  }
})

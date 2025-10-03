import { fileURLToPath, URL } from 'node:url'
import { defineConfig, type UserConfig } from 'vite'
import react from '@vitejs/plugin-react'
import federation from '@originjs/vite-plugin-federation'

export default defineConfig(({ mode }) => {
  const isScenario = mode === 'scenario'
  const isTest = mode === 'test'

  const aliases: Record<string, string> = {
    '@ph/shell': fileURLToPath(new URL('./src/shell/index.ts', import.meta.url)),
  }

  if (isTest) {
    aliases['@ph/scenario/ScenarioApp'] = fileURLToPath(
      new URL('./src/__mocks__/scenarioAppRemote.tsx', import.meta.url),
    )
  }

  const federationOptions: Parameters<typeof federation>[0] = {
    name: '@ph/scenario',
    filename: 'remoteEntry.js',
    exposes: {
      './ScenarioApp': './src/scenario/remoteEntry.ts',
    },
    shared: {
      react: { singleton: true, eager: true, requiredVersion: '^19.1.1' },
      'react-dom': { singleton: true, eager: true, requiredVersion: '^19.1.1' },
      zustand: { singleton: true, requiredVersion: '^5.0.8' },
    } as unknown as Parameters<typeof federation>[0]['shared'],
  }

  if (!isScenario && !isTest) {
    const defaultRemoteUrl =
      mode === 'development' ? 'http://localhost:5173/assets/remoteEntry.js' : '/assets/remoteEntry.js'

    const scenarioRemoteUrl = process.env.VITE_SCENARIO_REMOTE_URL ?? defaultRemoteUrl

    federationOptions.remotes = {
      '@ph/scenario': {
        external: scenarioRemoteUrl,
        externalType: 'url',
        from: 'vite',
      },
    } as Parameters<typeof federation>[0]['remotes']
  }

  const config: UserConfig = {
    plugins: [react(), federation(federationOptions)],
    publicDir: 'assets',
    build: {
      target: 'esnext',
      modulePreload: false,
      outDir: isScenario ? 'dist/scenario' : 'dist',
    },
    resolve: {
      alias: aliases,
    },
    test: {
      environment: 'jsdom',
    },
  }

  if (isScenario) {
    config.optimizeDeps = {
      entries: ['src/scenario/remoteEntry.ts'],
    }
  }

  return config
})

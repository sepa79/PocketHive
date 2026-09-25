import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const pluginMode = process.env.PLUGIN_MODE === 'true'
const proxyTarget = process.env.VITE_POCKETHIVE_DEV_PROXY_TARGET?.trim()
const backendProxy = proxyTarget
  ? {
      target: proxyTarget,
      changeOrigin: true,
      secure: false,
    }
  : undefined

export default defineConfig({
  // In plugin mode: relative base so webview can load assets from the filesystem.
  // In normal mode: serve as the primary gateway UI.
  base: pluginMode ? './' : '/',
  plugins: [react(), {
    name: 'shared-auth-session-entry',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((request, _response, next) => {
        if (request.url === '/auth-session.js') request.url = '/src/lib/authSession.ts'
        next()
      })
    },
  }],
  define: {
    __PLUGIN_MODE__: pluginMode,
  },
  build: pluginMode
    ? {
        outDir: '../vscode-pockethive/resources/dist-plugin',
        emptyOutDir: true,
      }
    : {
        rollupOptions: {
          input: { index: 'index.html', 'auth-session': 'src/lib/authSession.ts' },
          preserveEntrySignatures: 'strict',
          output: {
            entryFileNames: chunk => chunk.name === 'auth-session' ? 'auth-session.js' : 'assets/[name]-[hash].js',
          },
        },
      },
  server: backendProxy
    ? {
        proxy: {
          '/auth-service': backendProxy,
          '/tcp-mock': backendProxy,
          '/scenario-manager': backendProxy,
          '/orchestrator': backendProxy,
          '/network-proxy-manager': backendProxy,
        },
      }
    : undefined,
})

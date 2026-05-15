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
  // In normal mode: absolute base under the gateway prefix.
  base: pluginMode ? './' : '/v2/',
  plugins: [react()],
  define: {
    __PLUGIN_MODE__: pluginMode,
  },
  build: pluginMode
    ? {
        outDir: '../vscode-pockethive/resources/dist-plugin',
        emptyOutDir: true,
      }
    : undefined,
  server: backendProxy
    ? {
        proxy: {
          '/auth-service': backendProxy,
          '/scenario-manager': backendProxy,
          '/orchestrator': backendProxy,
          '/network-proxy-manager': backendProxy,
        },
      }
    : undefined,
})

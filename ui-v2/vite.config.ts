import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const proxyTarget = process.env.VITE_POCKETHIVE_DEV_PROXY_TARGET?.trim()
const backendProxy = proxyTarget
  ? {
      target: proxyTarget,
      changeOrigin: true,
      secure: false,
    }
  : undefined

export default defineConfig({
  // The UI is served under `/v2/*` by the PocketHive gateway. Use an absolute base so deep links
  // like `/v2/hive/...` don't resolve assets as `/v2/hive/assets/*` (which breaks with SPA fallbacks).
  base: '/v2/',
  plugins: [react()],
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

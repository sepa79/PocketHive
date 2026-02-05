import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  // The UI is served under `/v2/*` by the PocketHive gateway. Use an absolute base so deep links
  // like `/v2/hive/...` don't resolve assets as `/v2/hive/assets/*` (which breaks with SPA fallbacks).
  base: '/v2/',
  plugins: [react()],
})

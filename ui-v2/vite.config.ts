import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const pluginMode = process.env.PLUGIN_MODE === 'true';

export default defineConfig({
  // In plugin mode: relative base so webview can load assets from the filesystem.
  // In normal mode: absolute base under the gateway prefix.
  base: pluginMode ? './' : '/v2/',
  plugins: [react()],
  define: {
    // Exposes __PLUGIN_MODE__ as a compile-time constant consumed by pluginBridge.ts
    __PLUGIN_MODE__: pluginMode,
  },
  build: pluginMode ? {
    outDir: '../vscode-pockethive/resources/dist-plugin',
    emptyOutDir: true,
  } : undefined,
})

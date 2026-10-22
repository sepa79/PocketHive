import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['tools/mcp-orchestrator-debug/**/*.test.mjs'],
  },
})

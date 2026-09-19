import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['scripts/**/*.test.mjs', 'tools/mcp-orchestrator-debug/**/*.test.mjs'],
  },
})

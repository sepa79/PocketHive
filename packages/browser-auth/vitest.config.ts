import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    restoreMocks: true,
    environmentOptions: { jsdom: { url: 'http://localhost:49152/' } },
  },
})

import { expect, test } from 'vitest'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'

test('development serves the shared session entry as JavaScript for the TCP UI', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../../vite.config.ts', import.meta.url)),
    server: { host: '127.0.0.1', port: 0 },
  })
  try {
    await server.listen()
    const address = server.httpServer?.address()
    if (!address || typeof address === 'string') throw new Error('Missing test listener')
    const response = await fetch(`http://127.0.0.1:${address.port}/auth-session.js`)
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toContain('javascript')
    expect(await response.text()).toContain('readStoredAuthSession')
  } finally {
    await server.close()
  }
}, 15000)

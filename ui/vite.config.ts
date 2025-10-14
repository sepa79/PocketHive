import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { copyFile, mkdir } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'

const filePath = fileURLToPath(new URL('.', import.meta.url))
const projectRoot = resolve(filePath)
const repoRoot = resolve(projectRoot, '..')

const docTargets = [
  { src: resolve(repoRoot, 'README.md'), dest: resolve(projectRoot, 'assets/docs/README.md') },
  {
    src: resolve(repoRoot, 'docs/rules/control-plane-rules.md'),
    dest: resolve(projectRoot, 'assets/docs/rules/control-plane-rules.md'),
  },
  { src: resolve(repoRoot, 'CHANGELOG.md'), dest: resolve(projectRoot, 'assets/docs/CHANGELOG.md') },
  { src: resolve(repoRoot, 'docs/spec/asyncapi.yaml'), dest: resolve(projectRoot, 'assets/docs/spec/asyncapi.yaml') },
]

async function copyDocs() {
  await Promise.all(
    docTargets.map(async ({ src, dest }) => {
      await mkdir(dirname(dest), { recursive: true })
      await copyFile(src, dest)
    }),
  )
}

function docsCopyPlugin(): Plugin {
  return {
    name: 'pockethive-docs-copy',
    async buildStart() {
      await copyDocs()
    },
    configureServer(server) {
      const watched = docTargets.map(({ src }) => src)
      const handleChange = (path: string) => {
        if (watched.includes(path)) {
          void copyDocs()
        }
      }
      server.watcher.add(watched)
      server.watcher.on('change', handleChange)
      server.watcher.on('add', handleChange)
      server.httpServer?.once('close', () => {
        server.watcher.off('change', handleChange)
        server.watcher.off('add', handleChange)
      })
      void copyDocs()
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  plugins: [react() as any, docsCopyPlugin()],
  publicDir: 'assets',
  test: {
    environment: 'jsdom'
  }
})

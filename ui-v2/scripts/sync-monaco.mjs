import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const uiRoot = path.resolve(__dirname, '..')
const sourceDir = path.join(uiRoot, 'node_modules', 'monaco-editor', 'min', 'vs')
const targetDir = path.join(uiRoot, 'public', 'monaco', 'vs')

if (!existsSync(sourceDir)) {
  throw new Error(`Monaco source directory not found: ${sourceDir}`)
}

mkdirSync(path.dirname(targetDir), { recursive: true })
rmSync(targetDir, { recursive: true, force: true })
cpSync(sourceDir, targetDir, { recursive: true })

console.log(`Synced Monaco assets to ${targetDir}`)

#!/usr/bin/env node

import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, extname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse } from 'yaml'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const capabilitiesRoot = resolve(repositoryRoot, 'scenario-manager-service/capabilities')
const outputPath = resolve(repositoryRoot, 'tools/pockethive-mcp/generated/scenario-config-defaults.mjs')
const supportedExtensions = new Set(['.json', '.yaml', '.yml'])
const defaultsByImage = {}
const ioCapabilityBySelector = {}

for (const filename of readdirSync(capabilitiesRoot).sort()) {
  if (!supportedExtensions.has(extname(filename))) continue
  const manifest = parse(readFileSync(resolve(capabilitiesRoot, filename), 'utf8'))
  const imageName = requiredString(manifest?.image?.name, `${filename}: image.name`)
  const defaults = Object.fromEntries((manifest.config ?? [])
    .filter((entry) => entry && Object.hasOwn(entry, 'default'))
    .map((entry) => [requiredString(entry.name, `${filename}: config[].name`), entry.default]))
  if (Object.keys(defaults).length > 0) {
    if (defaultsByImage[imageName]) throw new Error(`Duplicate capability defaults for image '${imageName}'`)
    defaultsByImage[imageName] = defaults
  }

  const ioType = optionalString(manifest?.ui?.ioType)
  const ioScope = optionalString(manifest?.ui?.ioScope)?.toUpperCase()
  if (ioType && ioScope) {
    const selector = `${ioScope}|${ioType}`
    if (ioCapabilityBySelector[selector]) throw new Error(`Duplicate IO capability selector '${selector}'`)
    ioCapabilityBySelector[selector] = imageName
  }
}

const banner = '// Generated from scenario-manager-service/capabilities/*. Do not edit.\n'
const output = `${banner}
export const scenarioConfigDefaultsByImage = Object.freeze(${JSON.stringify(defaultsByImage, null, 2)})
export const scenarioIoCapabilityBySelector = Object.freeze(${JSON.stringify(ioCapabilityBySelector, null, 2)})

export function applyScenarioConfigDefaults(image, config = {}) {
  const result = clone(config)
  applyDefaults(result, scenarioConfigDefaultsByImage[canonicalImageName(image)])
  applyIoDefaults(result, 'INPUT', 'inputs')
  applyIoDefaults(result, 'OUTPUT', 'outputs')
  return result
}

function applyIoDefaults(config, scope, root) {
  const type = readPath(config, root + '.type')
  if (typeof type !== 'string' || type.length === 0) return
  const capabilityImage = scenarioIoCapabilityBySelector[scope + '|' + type]
  if (capabilityImage) applyDefaults(config, scenarioConfigDefaultsByImage[capabilityImage])
}

function applyDefaults(config, defaults) {
  if (!defaults) return
  for (const [path, value] of Object.entries(defaults)) setPathIfAbsent(config, path, clone(value))
}

function setPathIfAbsent(target, path, value) {
  const segments = path.split('.')
  let cursor = target
  for (const segment of segments.slice(0, -1)) {
    if (!isRecord(cursor[segment])) cursor[segment] = {}
    cursor = cursor[segment]
  }
  const leaf = segments.at(-1)
  if (!Object.hasOwn(cursor, leaf)) cursor[leaf] = value
}

function readPath(target, path) {
  let cursor = target
  for (const segment of path.split('.')) {
    if (!isRecord(cursor) || !Object.hasOwn(cursor, segment)) return undefined
    cursor = cursor[segment]
  }
  return cursor
}

function canonicalImageName(image) {
  if (typeof image !== 'string' || image.trim().length === 0) throw new Error('image must be a non-empty string')
  return image.trim().split('@', 1)[0].split('/').at(-1).split(':', 1)[0]
}

function clone(value) {
  if (Array.isArray(value)) return value.map(clone)
  if (!isRecord(value)) return value
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, clone(child)]))
}

function isRecord(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
`

if (process.argv.includes('--check')) {
  const actual = readFileSync(outputPath, 'utf8')
  if (actual !== output) throw new Error('scenario-config-defaults.mjs is stale; run npm run contracts:generate')
} else {
  mkdirSync(dirname(outputPath), { recursive: true })
  writeFileSync(outputPath, output)
}

function requiredString(value, label) {
  const normalized = optionalString(value)
  if (!normalized) throw new Error(`${label} must be a non-empty string`)
  return normalized
}

function optionalString(value) {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

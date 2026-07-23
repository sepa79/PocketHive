#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const schemaPath = resolve(repositoryRoot, 'docs/spec/swarm-lifecycle.schema.json')
const outputRoot = resolve(repositoryRoot, 'packages/swarm-lifecycle-contract')
const schema = JSON.parse(readFileSync(schemaPath, 'utf8'))
const definitions = schema.$defs

if (!definitions || typeof definitions !== 'object') {
  throw new Error('swarm lifecycle schema has no $defs')
}

const enumNames = Object.entries(definitions)
  .filter(([, definition]) => definition.type === 'string' && Array.isArray(definition.enum))
  .map(([name]) => name)

const banner = '// Generated from docs/spec/swarm-lifecycle.schema.json. Do not edit.\n'
const enumValues = Object.fromEntries(enumNames.map((name) => [name, definitions[name].enum]))
const esm = `${banner}
export const lifecycleEnumValues = Object.freeze(${JSON.stringify(enumValues, null, 2)})

export function parseControlResponse(value) {
  const record = requiredRecord(value, 'ControlResponse')
  return Object.freeze({
    correlationId: requiredString(record.correlationId, 'correlationId'),
    idempotencyKey: requiredString(record.idempotencyKey, 'idempotencyKey'),
    operationUrl: requiredString(record.operationUrl, 'operationUrl'),
    outcomeTopic: requiredString(record.outcomeTopic, 'outcomeTopic'),
    timeoutMs: requiredPositiveInteger(record.timeoutMs, 'timeoutMs'),
  })
}

export function parseOperationState(value) {
  return requiredEnum(value, 'OperationState')
}

export function parseTerminalStatus(value) {
  return requiredEnum(value, 'TerminalStatus')
}

export function parseControllerState(value) {
  return requiredEnum(value, 'ControllerState')
}

export function parseWorkloadState(value) {
  return requiredEnum(value, 'WorkloadState')
}

export function parseLifecycleAxes(value) {
  const record = requiredRecord(value, 'SwarmStateView')
  return Object.freeze({
    controllerState: requiredEnum(record.controllerState, 'ControllerState'),
    workloadState: requiredEnum(record.workloadState, 'WorkloadState'),
    health: requiredEnum(record.health, 'Health'),
    observationStale: requiredBoolean(record.observationStale, 'observationStale'),
  })
}

function requiredRecord(value, name) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(name + ' must be an object')
  }
  return value
}

function requiredString(value, field) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error('ControlResponse.' + field + ' must be a non-empty string')
  }
  return value.trim()
}

function requiredPositiveInteger(value, field) {
  if (!Number.isInteger(value) || value < 1) {
    throw new Error('ControlResponse.' + field + ' must be a positive integer')
  }
  return value
}

function requiredBoolean(value, field) {
  if (typeof value !== 'boolean') throw new Error('SwarmStateView.' + field + ' must be a boolean')
  return value
}

function requiredEnum(value, name) {
  if (typeof value !== 'string' || !lifecycleEnumValues[name].includes(value)) {
    throw new Error(name + " has unsupported value '" + String(value) + "'")
  }
  return value
}
`

const cjs = `${banner}
const lifecycleEnumValues = Object.freeze(${JSON.stringify(enumValues, null, 2)})

function parseControlResponse(value) {
  const record = requiredRecord(value, 'ControlResponse')
  return Object.freeze({
    correlationId: requiredString(record.correlationId, 'correlationId'),
    idempotencyKey: requiredString(record.idempotencyKey, 'idempotencyKey'),
    operationUrl: requiredString(record.operationUrl, 'operationUrl'),
    outcomeTopic: requiredString(record.outcomeTopic, 'outcomeTopic'),
    timeoutMs: requiredPositiveInteger(record.timeoutMs, 'timeoutMs'),
  })
}
function parseOperationState(value) { return requiredEnum(value, 'OperationState') }
function parseTerminalStatus(value) { return requiredEnum(value, 'TerminalStatus') }
function parseControllerState(value) { return requiredEnum(value, 'ControllerState') }
function parseWorkloadState(value) { return requiredEnum(value, 'WorkloadState') }
function parseLifecycleAxes(value) {
  const record = requiredRecord(value, 'SwarmStateView')
  return Object.freeze({
    controllerState: requiredEnum(record.controllerState, 'ControllerState'),
    workloadState: requiredEnum(record.workloadState, 'WorkloadState'),
    health: requiredEnum(record.health, 'Health'),
    observationStale: requiredBoolean(record.observationStale, 'observationStale'),
  })
}
function requiredRecord(value, name) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error(name + ' must be an object')
  return value
}
function requiredString(value, field) {
  if (typeof value !== 'string' || value.trim().length === 0) throw new Error('ControlResponse.' + field + ' must be a non-empty string')
  return value.trim()
}
function requiredPositiveInteger(value, field) {
  if (!Number.isInteger(value) || value < 1) throw new Error('ControlResponse.' + field + ' must be a positive integer')
  return value
}
function requiredBoolean(value, field) {
  if (typeof value !== 'boolean') throw new Error('SwarmStateView.' + field + ' must be a boolean')
  return value
}
function requiredEnum(value, name) {
  if (typeof value !== 'string' || !lifecycleEnumValues[name].includes(value)) throw new Error(name + " has unsupported value '" + String(value) + "'")
  return value
}
module.exports = { lifecycleEnumValues, parseControlResponse, parseOperationState, parseTerminalStatus, parseControllerState, parseWorkloadState, parseLifecycleAxes }
`

const declarations = Object.entries(definitions)
  .map(([name, definition]) => `export type ${name} = ${typescriptType(definition, 0)}`)
  .join('\n\n')
const dts = `${banner}
${declarations}

export type LifecycleAxes = Readonly<Pick<SwarmStateView,
  'controllerState' | 'workloadState' | 'health' | 'observationStale'>>

export const lifecycleEnumValues: Readonly<{
${enumNames.map((name) => `  ${name}: readonly ${name}[]`).join('\n')}
}>

export function parseControlResponse(value: unknown): Readonly<ControlResponse>
export function parseOperationState(value: unknown): OperationState
export function parseTerminalStatus(value: unknown): TerminalStatus
export function parseControllerState(value: unknown): ControllerState
export function parseWorkloadState(value: unknown): WorkloadState
export function parseLifecycleAxes(value: unknown): LifecycleAxes
`

const outputs = new Map([
  ['index.js', esm],
  ['index.cjs', cjs],
  ['index.d.ts', dts],
])
if (process.argv.includes('--check')) {
  for (const [name, expected] of outputs) {
    const actual = readFileSync(resolve(outputRoot, name), 'utf8')
    if (actual !== expected) {
      throw new Error(`${name} is stale; run npm run contracts:generate`)
    }
  }
} else {
  for (const [name, contents] of outputs) {
    writeFileSync(resolve(outputRoot, name), contents)
  }
}

function typescriptType(definition, depth) {
  if (!definition || typeof definition !== 'object') return 'unknown'
  if (definition.$ref) return definition.$ref.split('/').at(-1)
  if (Object.hasOwn(definition, 'const')) return JSON.stringify(definition.const)
  if (Array.isArray(definition.enum)) return definition.enum.map((value) => JSON.stringify(value)).join(' | ')
  if (Array.isArray(definition.type)) return definition.type.map(simpleType).join(' | ')
  if (definition.type === 'array') return `ReadonlyArray<${typescriptType(definition.items, depth)}>`
  if (definition.type === 'object' || definition.properties) {
    const required = new Set(definition.required ?? [])
    const properties = Object.entries(definition.properties ?? {}).map(([name, value]) =>
      `${'  '.repeat(depth + 1)}readonly ${JSON.stringify(name)}${required.has(name) ? '' : '?'}: ${typescriptType(value, depth + 1)}`)
    if (definition.additionalProperties === true) properties.push(`${'  '.repeat(depth + 1)}readonly [key: string]: unknown`)
    return properties.length === 0 ? 'Readonly<Record<string, unknown>>' : `{\n${properties.join('\n')}\n${'  '.repeat(depth)}}`
  }
  if (Array.isArray(definition.oneOf)) return definition.oneOf.map((item) => typescriptType(item, depth)).join(' | ')
  if (Array.isArray(definition.allOf)) return definition.allOf.map((item) => typescriptType(item, depth)).join(' & ')
  return simpleType(definition.type)
}

function simpleType(type) {
  if (type === 'integer' || type === 'number') return 'number'
  if (type === 'null') return 'null'
  if (type === 'boolean' || type === 'string') return type
  return 'unknown'
}

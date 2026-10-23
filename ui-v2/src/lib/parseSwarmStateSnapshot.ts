import Ajv, { type AnySchema, type ValidateFunction } from 'ajv/dist/2020'
import addFormats from 'ajv-formats'
import type { ControllerStatusFullContext, SwarmStateView, WorkerObservation } from '@pockethive/swarm-lifecycle-contract'
import lifecycleSchema from '@pockethive/swarm-lifecycle-contract/swarm-lifecycle.schema.json'
import controlSchema from '@pockethive/swarm-lifecycle-contract/control-events.schema.json'
import type { SwarmStateSnapshot } from './SwarmStateSnapshot'
import type { SwarmRuntimeWorker } from './SwarmRuntimeWorker'

const validator = new Ajv({ allErrors: false, strict: true, strictTypes: false, strictRequired: false })
addFormats(validator)
validator.addSchema(lifecycleSchema as AnySchema)
validator.addSchema(controlSchema as AnySchema)
const validateState = canonicalValidator<SwarmStateView>(lifecycleSchema.$id + '#/$defs/SwarmStateView')
const validateContext = canonicalValidator<ControllerStatusFullContext>(controlSchema.$id + '#/$defs/ControllerStatusFullContext')

function canonicalValidator<Value>(reference: string): ValidateFunction<Value> {
  const compiled = validator.getSchema<Value>(reference)
  if (!compiled) throw new Error('Canonical swarm schema is missing: ' + reference)
  return compiled
}

function validated<Value>(check: ValidateFunction<Value>, value: unknown, field: string): Value {
  if (!check(value)) {
    const error = check.errors?.[0]
    throw new Error('Invalid swarm state response: ' + field + (error?.instancePath ?? '') + ': ' + (error?.message ?? 'schema validation failed'))
  }
  return value
}

function projectWorker(entry: WorkerObservation): SwarmRuntimeWorker {
  const work = entry.ioState.work
  return {
    role: entry.role,
    instance: entry.instance,
    enabled: entry.enabled,
    tps: entry.tps,
    stale: entry.stale,
    lastSeenAt: entry.lastSeenAt,
    workIo: work ? { input: work.input, output: work.output } : null,
    runtime: entry.runtime ? { image: entry.runtime.image ?? null } : null,
    config: entry.config ?? null,
  }
}

/**
 * Responsibility: validate canonical REST/Controller schemas and project current Hive runtime details.
 * Must not: accept raw envelopes, duplicate field rules or infer lifecycle state/worker identity.
 * Contract: RESP-UI-SWARM-DETAIL — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-detail.
 */
export function parseSwarmStateSnapshot(value: unknown, swarmId: string): SwarmStateSnapshot {
  const state = validated(validateState, value, 'state')
  if (state.id !== swarmId) throw new Error('Invalid swarm state response: id does not match the selected swarm')
  const workers = Object.keys(state.observation).length === 0
    ? []
    : validated(validateContext, state.observation, 'observation').workers
  const instances = new Set(workers.map(entry => entry.instance))
  if (instances.size !== workers.length) throw new Error('Invalid swarm state response: duplicate worker instance')
  for (const entry of workers) {
    if (entry.runtime && entry.runtime.runId !== state.runId) {
      throw new Error('Invalid swarm state response: worker runtime.runId does not match the selected run')
    }
  }
  return {
    id: state.id,
    runId: state.runId,
    templateId: state.templateId,
    controllerImage: state.controllerImage,
    controllerState: state.controllerState,
    workloadState: state.workloadState,
    health: state.health,
    observedAt: state.observedAt,
    observationStale: state.observationStale,
    workers: workers.map(projectWorker),
  }
}

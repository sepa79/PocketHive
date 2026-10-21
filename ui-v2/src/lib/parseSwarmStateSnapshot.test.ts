import { describe, expect, it } from 'vitest'
import { parseSwarmStateSnapshot } from './parseSwarmStateSnapshot'
import { initialStateFixture, observedStateFixture } from '../testFixtures/swarmState'

const swarmId = 'synthetic-detail-swarm'

describe('canonical Orchestrator detail projection', () => {
  it('reads the actual DTO producer fixture with worker telemetry and config', () => {
    const payload = observedStateFixture()
    expect(parseSwarmStateSnapshot(payload, swarmId)).toMatchObject({
      id: payload.id, runId: payload.runId, templateId: payload.templateId,
      controllerImage: payload.controllerImage, controllerState: 'READY', workloadState: 'STOPPED',
      health: 'HEALTHY', observationStale: false, observedAt: payload.observedAt,
      workers: [{ instance: payload.observation.workers[0].instance, enabled: false, tps: 0,
        runtime: { image: payload.observation.workers[0].runtime.image },
        workIo: { input: 'ok', output: 'ok' }, config: payload.observation.workers[0].config }],
    })
  })
  it('uses owned state instead of conflicting cached axes', () => {
    const payload = observedStateFixture()
    Object.assign(payload.observation, { controllerState: 'FAILED', workloadState: 'RUNNING', health: 'FAILED' })
    expect(parseSwarmStateSnapshot(payload, swarmId)).toMatchObject({ controllerState: 'READY', workloadState: 'STOPPED', health: 'HEALTHY' })
  })
  it('reads the producer initial fixture without inventing observations', () => {
    expect(parseSwarmStateSnapshot(initialStateFixture(), swarmId)).toMatchObject({
      runId: 'synthetic-detail-run', controllerState: 'PROVISIONING', workloadState: 'UNAVAILABLE',
      health: 'UNKNOWN', observedAt: null, observationStale: true, workers: [],
    })
  })
  it('preserves multiple instances sharing a role', () => {
    const payload = observedStateFixture()
    const worker = { ...payload.observation.workers[0], instance: 'synthetic-generator-2' }
    payload.observation.workers.push(worker)
    payload.observation.expectedWorkers.push({ role: worker.role, instance: worker.instance })
    payload.bees.push({ role: worker.role, instance: worker.instance, image: worker.runtime.image })
    expect(parseSwarmStateSnapshot(payload, swarmId).workers.map(entry => entry.instance))
      .toEqual(['synthetic-detail-swarm-generator-1', 'synthetic-generator-2'])
  })
  it('distinguishes omitted config, empty config and invalid null', () => {
    const payload = observedStateFixture()
    const worker: Record<string, unknown> = payload.observation.workers[0]
    delete worker.runtime
    delete worker.config
    expect(parseSwarmStateSnapshot(payload, swarmId).workers[0]).toMatchObject({ runtime: null, config: null })
    worker.config = {}
    expect(parseSwarmStateSnapshot(payload, swarmId).workers[0].config).toEqual({})
    worker.config = null
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow('config')
  })
  it('allows explicit nullable metadata values, not missing keys', () => {
    const payload: Record<string, unknown> = initialStateFixture()
    payload.templateId = null
    payload.controllerImage = null
    expect(parseSwarmStateSnapshot(payload, swarmId)).toMatchObject({ templateId: null, controllerImage: null })
    delete payload.templateId
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow('templateId')
  })
  it('rejects the removed envelope wrapper instead of rendering empty success', () => {
    expect(() => parseSwarmStateSnapshot({ receivedAt: '2026-10-21T10:00:00Z', staleAfterSec: 15,
      envelope: { data: { context: observedStateFixture().observation } },
    }, swarmId)).toThrow('id')
  })
  it.each(['id', 'runId', 'runtimeIntent', 'workloadIntent', 'controllerState', 'workloadState', 'health',
    'runtimeResourceState', 'observedAt', 'observationStale', 'activeOperation', 'templateId', 'controllerImage', 'bees', 'observation'])(
    'requires REST property %s', field => {
    const payload: Record<string, unknown> = observedStateFixture()
    delete payload[field]
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow(field)
  })
  it.each([
    { override: { id: 'other' }, error: 'selected swarm' },
    { override: { runId: '' }, error: 'runId' },
    { override: { controllerState: 'BOGUS' }, error: 'controllerState' },
    { override: { workloadState: null }, error: 'workloadState' },
    { override: { health: false }, error: 'health' },
    { override: { observationStale: 'no' }, error: 'observationStale' },
    { override: { observedAt: 'not-a-date' }, error: 'observedAt' },
    { override: { observation: null }, error: 'observation' },
    { override: { observation: [] }, error: 'observation' },
    { override: { obsoleteStatus: 'RUNNING' }, error: 'additional properties' },
  ])('rejects malformed REST values: $override', ({ override, error }) => {
    expect(() => parseSwarmStateSnapshot({ ...observedStateFixture(), ...override }, swarmId)).toThrow(error)
  })
  it.each(['role', 'instance', 'enabled', 'tps', 'lastSeenAt', 'stale', 'ioState'])(
    'requires worker %s from the Controller schema', field => {
    const payload = observedStateFixture()
    const worker: Record<string, unknown> = payload.observation.workers[0]
    delete worker[field]
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow(field)
  })
  it.each([
    { field: 'enabled', value: 'yes' }, { field: 'tps', value: 12.5 }, { field: 'tps', value: -1 },
    { field: 'stale', value: null }, { field: 'lastSeenAt', value: 'yesterday' },
    { field: 'config', value: [] }, { field: 'runtime', value: null },
  ])('rejects invalid worker $field=$value', ({ field, value }) => {
    const payload = observedStateFixture()
    const worker: Record<string, unknown> = payload.observation.workers[0]
    worker[field] = value
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow(field)
  })
  it('rejects incomplete non-empty context instead of assuming no workers', () => {
    const payload = observedStateFixture()
    const context: Record<string, unknown> = payload.observation
    delete context.workers
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow('workers')
  })
  it('rejects a second unsupported runtime worker identity', () => {
    const payload = observedStateFixture()
    const worker: Record<string, unknown> = payload.observation.workers[0]
    worker.beeId = 'obsolete'
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow('additional properties')
  })
  it('rejects duplicate runtime identities without collapsing by role', () => {
    const payload = observedStateFixture()
    payload.observation.workers.push({ ...payload.observation.workers[0], role: 'processor' })
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow('duplicate worker instance')
  })
  it('rejects runtime metadata belonging to another run', () => {
    const payload = observedStateFixture()
    payload.observation.workers[0].runtime.runId = 'obsolete-run'
    expect(() => parseSwarmStateSnapshot(payload, swarmId)).toThrow('selected run')
  })
})

import { describe, expect, it } from 'vitest'
import {
  composeCapabilityConfigEntries,
  composeLiveCapabilityConfigEntries,
  type CapabilityManifest,
} from './capabilities'

function manifest(
  role: string,
  configNames: Array<string | { name: string; liveMutable?: boolean }>,
  ui?: Record<string, unknown>,
): CapabilityManifest {
  return {
    schemaVersion: '1.0',
    capabilitiesVersion: '1.0',
    role,
    image: { name: role },
    ui,
    config: configNames.map((entry) => {
      const value = typeof entry === 'string' ? { name: entry } : entry
      return { name: value.name, type: 'string', liveMutable: value.liveMutable }
    }),
    actions: [],
    panels: [],
  }
}

describe('composeCapabilityConfigEntries', () => {
  const worker = manifest('generator', ['inputs.type', 'message.body'])
  const scheduler = manifest('io-scheduler', ['inputs.scheduler.ratePerSec'], {
    ioScope: 'INPUT',
    ioType: 'SCHEDULER',
  })
  const redisInput = manifest('io-redis-dataset', ['inputs.redis.ratePerSec'], {
    ioScope: 'INPUT',
    ioType: 'REDIS_DATASET',
  })
  const redisOutput = manifest('io-redis-output', ['outputs.redis.host'], {
    ioScope: 'OUTPUT',
    ioType: 'REDIS',
  })
  const catalogue = [worker, scheduler, redisInput, redisOutput]

  it('adds IO fields selected by explicit runtime selectors', () => {
    const entries = composeCapabilityConfigEntries(worker, catalogue, {
      inputs: { type: 'SCHEDULER', scheduler: { ratePerSec: 50 } },
      outputs: { type: 'REDIS' },
      message: { body: 'hello' },
    })

    expect(entries.map((entry) => entry.name)).toEqual([
      'inputs.type',
      'message.body',
      'inputs.scheduler.ratePerSec',
      'outputs.redis.host',
    ])
  })

  it('does not infer IO fields from nested config without selectors', () => {
    const entries = composeCapabilityConfigEntries(worker, catalogue, {
      inputs: { scheduler: { ratePerSec: 50 } },
      message: { body: 'hello' },
    })

    expect(entries.map((entry) => entry.name)).toEqual(['inputs.type', 'message.body'])
  })

  it('does not use capability defaults as IO selectors', () => {
    const entries = composeCapabilityConfigEntries(worker, catalogue, null)

    expect(entries.map((entry) => entry.name)).toEqual(['inputs.type', 'message.body'])
  })

  it('keeps only explicitly live-mutable fields for runtime config editing', () => {
    const liveWorker = manifest('generator', [
      { name: 'inputs.type', liveMutable: false },
      { name: 'message.body', liveMutable: true },
      { name: 'message.headers' },
    ])
    const liveScheduler = manifest('io-scheduler', [
      { name: 'inputs.scheduler.ratePerSec', liveMutable: true },
      { name: 'inputs.scheduler.maxMessages', liveMutable: true },
    ], {
      ioScope: 'INPUT',
      ioType: 'SCHEDULER',
    })
    const liveRedisOutput = manifest('io-redis-output', [
      { name: 'outputs.redis.host', liveMutable: false },
      { name: 'outputs.redis.maxLen', liveMutable: false },
    ], {
      ioScope: 'OUTPUT',
      ioType: 'REDIS',
    })

    const entries = composeLiveCapabilityConfigEntries(
      liveWorker,
      [liveWorker, liveScheduler, liveRedisOutput],
      {
        inputs: { type: 'SCHEDULER' },
        outputs: { type: 'REDIS' },
      },
    )

    expect(entries.map((entry) => entry.name)).toEqual([
      'message.body',
      'inputs.scheduler.ratePerSec',
      'inputs.scheduler.maxMessages',
    ])
  })
})

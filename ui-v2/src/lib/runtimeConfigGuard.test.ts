import { describe, expect, it } from 'vitest'
import { changesRedisDatasetListName, isStoppedSwarmStatus } from './runtimeConfigGuard'

describe('runtime config guard', () => {
  it('recognizes only an explicit Redis dataset listName patch', () => {
    expect(changesRedisDatasetListName({ inputs: { redis: { listName: 'cards.TOP' } } })).toBe(true)
    expect(changesRedisDatasetListName({ inputs: { redis: { ratePerSec: 10 } } })).toBe(false)
    expect(changesRedisDatasetListName({ inputs: { csv: { ratePerSec: 10 } } })).toBe(false)
  })

  it('accepts STOPPED case-insensitively and fails closed for missing or other states', () => {
    expect(isStoppedSwarmStatus('STOPPED')).toBe(true)
    expect(isStoppedSwarmStatus('Stopped')).toBe(true)
    expect(isStoppedSwarmStatus(' RUNNING ')).toBe(false)
    expect(isStoppedSwarmStatus(null)).toBe(false)
  })
})

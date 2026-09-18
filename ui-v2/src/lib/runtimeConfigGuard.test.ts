import { describe, expect, it } from 'vitest'
import { changesRedisDatasetListName, isStoppedWorkloadState } from './runtimeConfigGuard'

describe('runtime config guard', () => {
  it('recognizes only an explicit Redis dataset listName patch', () => {
    expect(changesRedisDatasetListName({ inputs: { redis: { listName: 'cards.TOP' } } })).toBe(true)
    expect(changesRedisDatasetListName({ inputs: { redis: { ratePerSec: 10 } } })).toBe(false)
    expect(changesRedisDatasetListName({ inputs: { csv: { ratePerSec: 10 } } })).toBe(false)
  })

  it('accepts STOPPED case-insensitively and fails closed for missing or other states', () => {
    expect(isStoppedWorkloadState('STOPPED')).toBe(true)
    expect(isStoppedWorkloadState('Stopped')).toBe(true)
    expect(isStoppedWorkloadState(' RUNNING ')).toBe(false)
    expect(isStoppedWorkloadState(null)).toBe(false)
  })
})

import { describe, expect, it } from 'vitest'
import { heartbeatHealth, queueHealth, thresholds } from './health'

describe('heartbeatHealth', () => {
  it('returns OK for recent heartbeat', () => {
    const ts = Date.now()
    expect(heartbeatHealth(ts)).toBe('OK')
  })
  it('returns WARN when heartbeat old', () => {
    const ts = Date.now() - (thresholds.heartbeatWarn + 1) * 1000
    expect(heartbeatHealth(ts)).toBe('WARN')
  })
  it('returns ALERT when heartbeat very old', () => {
    const ts = Date.now() - (thresholds.heartbeatAlert + 1) * 1000
    expect(heartbeatHealth(ts)).toBe('ALERT')
  })
})

describe('queueHealth', () => {
  it('returns OK for small depth', () => {
    expect(queueHealth({ name: 'q', role: 'producer', depth: 1 })).toBe('OK')
  })
  it('returns WARN for medium depth', () => {
    expect(queueHealth({ name: 'q', role: 'producer', depth: thresholds.depthWarn + 1 })).toBe('WARN')
  })
  it('returns ALERT for large depth', () => {
    expect(queueHealth({ name: 'q', role: 'producer', depth: thresholds.depthAlert + 1 })).toBe('ALERT')
  })
})


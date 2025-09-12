import { describe, expect, it } from 'vitest'
import { logHandshake, logError, subscribeLogs, type LogEntry, resetLogs } from './logs'

/**
 * @vitest-environment jsdom
 */

describe('logs', () => {
  it('collects handshake entries with metadata', () => {
    resetLogs()
    let entries: LogEntry[] = []
    const unsubscribe = subscribeLogs((l) => {
      entries = l
    })
    logHandshake(
      '/exchange/ph.control/ev.ready.swarm-controller.inst',
      '{}',
      'hive',
      'stomp',
      'abc',
    )
    unsubscribe()
    expect(entries).toHaveLength(1)
    expect(entries[0].destination).toContain('ev.ready.swarm-controller.inst')
    expect(entries[0].source).toBe('hive')
    expect(entries[0].channel).toBe('stomp')
    expect(entries[0].correlationId).toBe('abc')
  })

  it('collects error entries', () => {
    resetLogs()
    let entries: LogEntry[] = []
    const unsubscribe = subscribeLogs((l) => {
      entries = l
    })
    logError(
      '/exchange/ph.control/ev.swarm-create.error.sw1',
      'boom',
      'hive',
      'stomp',
    )
    unsubscribe()
    expect(entries).toHaveLength(1)
    expect(entries[0].destination).toContain('ev.swarm-create.error.sw1')
    expect(entries[0].body).toBe('boom')
  })
})

import { describe, expect, it } from 'vitest'
import { logError, subscribeLogs, type LogEntry, resetLogs } from './logs'

/**
 * @vitest-environment jsdom
 */

describe('logs', () => {
  it('collects error entries', () => {
    resetLogs()
    let entries: LogEntry[] = []
    const unsubscribe = subscribeLogs((l) => {
      entries = l
    })
    logError(
      '/exchange/ph.control/ev.error.swarm-create.sw1',
      'boom',
      'hive',
      'stomp',
    )
    unsubscribe()
    expect(entries).toHaveLength(1)
    expect(entries[0].destination).toContain('ev.error.swarm-create.sw1')
    expect(entries[0].body).toBe('boom')
  })
})

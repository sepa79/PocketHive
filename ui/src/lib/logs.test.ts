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
      '/exchange/ph.control/event.alert.alert.sw1.swarm-controller.inst',
      'boom',
      'hive',
      'stomp',
    )
    unsubscribe()
    expect(entries).toHaveLength(1)
    expect(entries[0].destination).toContain('event.alert.alert.sw1')
    expect(entries[0].body).toBe('boom')
  })
})

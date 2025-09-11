import { describe, expect, it } from 'vitest'
import { logHandshake, logError, subscribeLogs, type LogEntry, resetLogs } from './logs'

/**
 * @vitest-environment jsdom
 */

describe('handshake logs', () => {
  it('collects handshake entries', () => {
    resetLogs()
    let entries: LogEntry[] = []
    const unsubscribe = subscribeLogs('handshake', (l) => {
      entries = l
    })
    logHandshake('/exchange/ph.control/ev.ready.swarm-controller.inst', '{}')
    unsubscribe()
    expect(entries).toHaveLength(1)
    expect(entries[0].destination).toContain('ev.ready.swarm-controller.inst')
  })
})

describe('error logs', () => {
  it('collects error entries', () => {
    resetLogs()
    let entries: LogEntry[] = []
    const unsubscribe = subscribeLogs('error', (l) => {
      entries = l
    })
    logError('/exchange/ph.control/sig.swarm-create.error.sw1', 'boom')
    unsubscribe()
    expect(entries).toHaveLength(1)
    expect(entries[0].destination).toContain('sig.swarm-create.error.sw1')
    expect(entries[0].body).toBe('boom')
  })
})

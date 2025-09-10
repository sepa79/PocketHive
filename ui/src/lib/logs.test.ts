import { describe, expect, it } from 'vitest'
import { logHandshake, subscribeLogs, type LogEntry } from './logs'

/**
 * @vitest-environment jsdom
 */

describe('handshake logs', () => {
  it('collects handshake entries', () => {
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

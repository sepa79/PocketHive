import { describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { setClient } from './stompClient'
import { subscribeLogs, type LogEntry, resetLogs } from './logs'
import { useUIStore } from '@ph/shell'

/**
 * @vitest-environment jsdom
 */


describe('swarm lifecycle', () => {
  it('logs error events and sets toast', () => {
    resetLogs()
    useUIStore.setState({ toast: null })
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)
    let entries: LogEntry[] = []
    subscribeLogs((l) => {
      entries = l.filter((e) => e.type === 'error')
    })
    cb({
      body: 'boom',
      headers: { destination: '/exchange/ph.control/ev.error.swarm-create.sw1', 'x-correlation-id': 'e1' },
    })
    expect(entries[0].destination).toContain('ev.error.swarm-create.sw1')
    expect(entries[0].body).toBe('boom')
    expect(useUIStore.getState().toast).toBe('Error: error swarm-create sw1: boom')
    setClient(null)
  })
})

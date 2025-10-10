import { describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { setClient, subscribeComponents } from './stompClient'
import { subscribeLogs, type LogEntry, resetLogs } from './logs'
import { useUIStore } from '../store'
import type { Component } from '../types/hive'

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

  it('propagates queue stats to all component queues', () => {
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)

    const updates: Component[][] = []
    const unsubscribe = subscribeComponents((list) => {
      updates.push(
        list.map((comp) => ({
          ...comp,
          queues: comp.queues.map((q) => ({ ...q })),
        })),
      )
    })

    const baseHeaders = { destination: '/exchange/ph.control/ev.status.swarm-sw1' }
    cb({
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'processor',
        instance: 'processor-sw1',
        messageId: 'm-1',
        timestamp: new Date().toISOString(),
        queues: {
          work: {
            out: ['ph.sw1.jobs'],
          },
        },
      }),
    })

    cb({
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'swarm-controller',
        instance: 'controller-sw1',
        messageId: 'm-2',
        timestamp: new Date().toISOString(),
        queueStats: {
          'ph.sw1.jobs': {
            depth: 3,
            consumers: 2,
            oldestAgeSec: 12,
          },
        },
      }),
    })

    const latest = updates.at(-1)
    expect(latest).toBeTruthy()
    const processor = latest?.find((comp) => comp.id === 'processor-sw1')
    expect(processor).toBeTruthy()
    expect(processor?.queues[0]).toMatchObject({
      name: 'ph.sw1.jobs',
      role: 'producer',
      depth: 3,
      consumers: 2,
      oldestAgeSec: 12,
    })

    unsubscribe()
    setClient(null)
  })
})

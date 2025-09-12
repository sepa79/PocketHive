import { describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { setClient, createSwarm, startSwarm, stopSwarm } from './stompClient'
import { subscribeLogs, type LogEntry, resetLogs } from './logs'
import { useUIStore } from '../store'

/**
 * @vitest-environment jsdom
 */


describe('swarm lifecycle', () => {
  it('publishes swarm creation signal', async () => {
    const publish = vi.fn()
    const subscribe = vi.fn().mockReturnValue({ unsubscribe() {} })
    setClient({ active: true, publish, subscribe } as unknown as Client)
    await createSwarm('sw1', 'rest')
    expect(publish).toHaveBeenCalledWith({
      destination: '/exchange/ph.control/sig.swarm-create.sw1',
      body: JSON.stringify({ templateId: 'rest' }),
    })
  })

  it('publishes swarm start signal', async () => {
    const publish = vi.fn()
    const subscribe = vi.fn().mockReturnValue({ unsubscribe() {} })
    setClient({ active: true, publish, subscribe } as unknown as Client)
    await startSwarm('sw1')
    expect(publish).toHaveBeenCalledWith({
      destination: '/exchange/ph.control/sig.swarm-start.sw1',
      body: '',
    })
  })

  it('publishes swarm stop signal', async () => {
    const publish = vi.fn()
    const subscribe = vi.fn().mockReturnValue({ unsubscribe() {} })
    setClient({ active: true, publish, subscribe } as unknown as Client)
    await stopSwarm('sw1')
    expect(publish).toHaveBeenCalledWith({
      destination: '/exchange/ph.control/sig.swarm-stop.sw1',
      body: '',
    })
  })

  it('logs handshake events', () => {
    resetLogs()
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi.fn().mockImplementation(
      (_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      },
    )
    setClient({ active: true, publish, subscribe } as unknown as Client)
    let entries: LogEntry[] = []
    subscribeLogs('handshake', (l) => {
      entries = l
    })
    cb({ body: '{}', headers: { destination: '/exchange/ph.control/ev.ready.swarm-controller.inst' } })
    cb({ body: '{}', headers: { destination: '/exchange/ph.control/sig.swarm-template.sw1' } })
    expect(entries.some((e) => e.destination.includes('ev.ready.swarm-controller.inst'))).toBe(true)
    expect(entries[entries.length - 1].destination).toContain('sig.swarm-template.sw1')
  })

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
    subscribeLogs('error', (l) => {
      entries = l
    })
    cb({ body: 'boom', headers: { destination: '/exchange/ph.control/ev.swarm-create.error.sw1' } })
    expect(entries[0].destination).toContain('ev.swarm-create.error.sw1')
    expect(entries[0].body).toBe('boom')
    expect(useUIStore.getState().toast).toBe('Error: swarm-create error sw1: boom')
  })
})

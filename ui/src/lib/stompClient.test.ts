import { describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import { setClient, createSwarm, startSwarm, stopSwarm } from './stompClient'
import { subscribeLogs, type LogEntry } from './logs'

/**
 * @vitest-environment jsdom
 */


describe('swarm lifecycle', () => {
  it('publishes swarm creation signal', async () => {
    const publish = vi.fn()
    const subscribe = vi.fn().mockReturnValue({ unsubscribe() {} })
    setClient({ active: true, publish, subscribe } as unknown as Client)
    await createSwarm('sw1', 'img:latest')
    expect(publish).toHaveBeenCalledWith({
      destination: '/exchange/ph.control/sig.swarm-create.sw1',
      body: 'img:latest',
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
    expect(entries[0].destination).toContain('ev.ready.swarm-controller.inst')
  })
})

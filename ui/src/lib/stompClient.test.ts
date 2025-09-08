import { describe, expect, it, vi } from 'vitest'
import { setClient, createSwarm, startSwarm, stopSwarm } from './stompClient'

/**
 * @vitest-environment jsdom
 */


describe('swarm lifecycle', () => {
  it('publishes swarm creation signal', async () => {
    const publish = vi.fn()
    const subscribe = vi.fn().mockReturnValue({ unsubscribe() {} })
    setClient({ active: true, publish, subscribe } as any)
    await createSwarm('sw1', 'img:latest')
    expect(publish).toHaveBeenCalledWith({
      destination: '/exchange/ph.control/sig.swarm-create.sw1',
      body: 'img:latest',
    })
  })

  it('publishes swarm start signal', async () => {
    const publish = vi.fn()
    const subscribe = vi.fn().mockReturnValue({ unsubscribe() {} })
    setClient({ active: true, publish, subscribe } as any)
    await startSwarm('sw1')
    expect(publish).toHaveBeenCalledWith({
      destination: '/exchange/ph.control/sig.swarm-start.sw1',
      body: '',
    })
  })

  it('publishes swarm stop signal', async () => {
    const publish = vi.fn()
    const subscribe = vi.fn().mockReturnValue({ unsubscribe() {} })
    setClient({ active: true, publish, subscribe } as any)
    await stopSwarm('sw1')
    expect(publish).toHaveBeenCalledWith({
      destination: '/exchange/ph.control/sig.swarm-stop.sw1',
      body: '',
    })
  })
})

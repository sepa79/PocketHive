import { describe, expect, it, vi } from 'vitest'
import { setClient, startSwarm } from './stompClient'

/**
 * @vitest-environment jsdom
 */

describe('startSwarm', () => {
  it('publishes swarm creation signal', async () => {
    const publish = vi.fn()
    const subscribe = vi.fn().mockReturnValue({ unsubscribe() {} })
    setClient({ active: true, publish, subscribe } as any)
    await startSwarm('sw1', 'img:latest')
    expect(publish).toHaveBeenCalledWith({
      destination: '/exchange/ph.control/sig.swarm-create.sw1',
      body: 'img:latest',
    })
  })
})

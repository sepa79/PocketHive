/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom/vitest'
import { vi, test, expect, beforeEach } from 'vitest'
import HivePage from './HivePage'
import type { Component } from '../../types/hive'
import { subscribeComponents, startSwarm, stopSwarm, requestStatusFull } from '../../lib/stompClient'

vi.mock('../../lib/stompClient', () => ({
  subscribeComponents: vi.fn(),
  startSwarm: vi.fn(),
  stopSwarm: vi.fn(),
  requestStatusFull: vi.fn(),
}))

vi.mock('./TopologyView', () => ({
  default: () => null,
}))

const comps: Component[] = [
  {
    id: 'sw1-marshal',
    name: 'swarm-controller',
    swarmId: 'sw1',
    lastHeartbeat: 0,
    queues: [],
    config: { swarmStatus: 'STOPPED', enabled: true },
  },
]

let listener: ((c: Component[]) => void) | null = null

beforeEach(() => {
  ;(subscribeComponents as unknown as any).mockImplementation((fn: (c: Component[]) => void) => {
    listener = fn
    fn(comps)
    return () => {}
  })
  ;(startSwarm as unknown as any).mockReset()
  ;(stopSwarm as unknown as any).mockReset()
  ;(requestStatusFull as unknown as any).mockResolvedValue(undefined)
})

test('renders marshal status and start/stop controls', async () => {
  const user = userEvent.setup()
  render(<HivePage />)
  expect(screen.getByText(/Marshal: stopped/i)).toBeTruthy()
  await user.click(screen.getByRole('button', { name: /start/i }))
  expect(startSwarm).toHaveBeenCalledWith('sw1')

  comps[0].config = { swarmStatus: 'RUNNING', enabled: true }
  listener && listener([...comps])
  expect(await screen.findByText(/Marshal: running/i)).toBeTruthy()
  await user.click(screen.getByRole('button', { name: /stop/i }))
  expect(stopSwarm).toHaveBeenCalledWith('sw1')
})

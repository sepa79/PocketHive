/**
 * @vitest-environment jsdom
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom/vitest'
import { vi, test, expect, beforeEach, afterEach, type Mock, type SpyInstance } from 'vitest'
import HivePage from './HivePage'
import type { Component } from '../../types/hive'
import { subscribeComponents } from '../../lib/stompClient'
import * as apiModule from '../../lib/api'

vi.mock('../../lib/stompClient', () => ({
  subscribeComponents: vi.fn(),
}))

vi.mock('./TopologyView', () => ({
  default: () => null,
}))

const baseComponents: Component[] = [
  {
    id: 'sw1-queen',
    name: 'swarm-controller',
    swarmId: 'sw1',
    lastHeartbeat: 0,
    queues: [],
    config: { swarmStatus: 'STOPPED', enabled: true },
  },
  {
    id: 'orphan',
    name: 'generator',
    lastHeartbeat: 0,
    queues: [],
    config: { enabled: true },
  },
]

let listener: ((c: Component[]) => void) | null = null
let comps: Component[] = []
let apiFetchSpy: SpyInstance
beforeEach(() => {
  listener = null
  comps = baseComponents.map((c) => ({
    ...c,
    config: c.config ? { ...c.config } : undefined,
    queues: [...c.queues],
  }))
  ;(subscribeComponents as unknown as Mock).mockImplementation(
    (fn: (c: Component[]) => void) => {
      listener = fn
      fn(comps)
      return () => {}
    },
  )
  apiFetchSpy = vi.spyOn(apiModule, 'apiFetch').mockResolvedValue({} as Response)
})

afterEach(() => {
  apiFetchSpy.mockRestore()
  vi.clearAllMocks()
})

test('renders queen status and start/stop controls', async () => {
  const user = userEvent.setup()
  render(<HivePage />)
  expect(screen.getByText(/Queen: stopped/i)).toBeTruthy()
  const startBtn = screen.getByRole('button', { name: /start/i })
  await user.click(startBtn)
  await waitFor(() =>
    expect(apiFetchSpy).toHaveBeenCalledWith(
      '/orchestrator/swarms/sw1/start',
      expect.objectContaining({ method: 'POST' }),
    ),
  )
  expect(await screen.findByText('Swarm started')).toBeTruthy()

  comps[0].config = { swarmStatus: 'RUNNING', enabled: true }
  if (listener) listener([...comps])
  expect(await screen.findByText(/Queen: running/i)).toBeTruthy()
  await user.click(screen.getAllByRole('button', { name: /stop/i })[1])
  await waitFor(() =>
    expect(apiFetchSpy).toHaveBeenCalledWith(
      '/orchestrator/swarms/sw1/stop',
      expect.objectContaining({ method: 'POST' }),
    ),
  )
  expect(await screen.findByText('Swarm stopped')).toBeTruthy()
})

test('shows unassigned components when selecting default swarm', async () => {
  const user = userEvent.setup()
  render(<HivePage />)
  const [def] = screen.getAllByText('default')
  expect(def).toBeTruthy()
  await user.click(def)
  const gens = await screen.findAllByText('generator')
  expect(gens.length).toBeGreaterThan(0)
})

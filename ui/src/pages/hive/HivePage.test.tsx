/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom/vitest'
import { vi, test, expect, beforeEach, afterEach } from 'vitest'
import HivePage from './HivePage'
import type { Component } from '../../types/hive'
import { subscribeComponents } from '../../lib/stompClient'

vi.mock('../../lib/stompClient', () => ({
  subscribeComponents: vi.fn(),
}))

vi.mock('./TopologyView', () => ({
  default: () => null,
}))

const baseComponents: Component[] = [
  {
    id: 'sw1-queen',
    name: 'sw1-queen',
    role: 'swarm-controller',
    swarmId: 'sw1',
    lastHeartbeat: 0,
    queues: [],
    config: { swarmStatus: 'STOPPED', enabled: true },
  },
  {
    id: 'orphan',
    name: 'orphan',
    role: 'generator',
    lastHeartbeat: 0,
    queues: [],
    config: { enabled: true },
  },
]

let listener: ((c: Component[]) => void) | null = null
let comps: Component[] = []
beforeEach(() => {
  listener = null
  comps = baseComponents.map((c) => ({
    ...c,
    config: c.config ? { ...c.config } : undefined,
    queues: [...c.queues],
  }))
  vi.mocked(subscribeComponents).mockImplementation(
    (fn: (c: Component[]) => void) => {
      listener = fn
      fn(comps)
      return () => {}
    },
  )
})

afterEach(() => {
  vi.clearAllMocks()
})

test('renders queen status without start/stop controls', async () => {
  render(<HivePage />)
  expect(await screen.findByText(/Queen: stopped/i)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /start/i })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /stop/i })).not.toBeInTheDocument()

  comps[0].config = { swarmStatus: 'RUNNING', enabled: true }
  // Push-style refresh: mimic an incoming `ev.status-*` notification from the control plane.
  if (listener) listener([...comps])
  expect(await screen.findByText(/Queen: running/i)).toBeInTheDocument()
})

test('shows unassigned components when selecting default swarm', async () => {
  const user = userEvent.setup()
  render(<HivePage />)
  const [def] = screen.getAllByText('default')
  expect(def).toBeTruthy()
  await user.click(def)
  const gens = await screen.findAllByText(
    (_content, element) => element?.textContent?.trim() === 'generator',
  )
  expect(gens.length).toBeGreaterThan(0)
})

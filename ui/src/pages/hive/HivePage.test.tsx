/**
 * @vitest-environment jsdom
 */
import { render, screen, within } from '@testing-library/react'
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

let comps: Component[] = []
beforeEach(() => {
  comps = baseComponents.map((c) => ({
    ...c,
    config: c.config ? { ...c.config } : undefined,
    queues: [...c.queues],
  }))
  vi.mocked(subscribeComponents).mockImplementation(
    (fn: (c: Component[]) => void) => {
      fn(comps)
      return () => {}
    },
  )
})

afterEach(() => {
  vi.clearAllMocks()
})

test('renders orchestrator panel inactive when orchestrator is missing', () => {
  render(<HivePage />)
  const panels = screen.getAllByTestId('orchestrator-panel')
  expect(panels.length).toBeGreaterThan(0)
  const panel = panels[panels.length - 1]!
  const scope = within(panel)
  expect(scope.getAllByText('Orchestrator').length).toBeGreaterThan(0)
  expect(scope.getByText('Not detected')).toBeInTheDocument()
  expect(scope.getByRole('button', { name: 'Start all' })).toBeDisabled()
  expect(scope.getByRole('button', { name: 'Stop all' })).toBeDisabled()
})

test('enables orchestrator controls when orchestrator component is present', async () => {
  comps.push({
    id: 'hive-orchestrator',
    name: 'HAL',
    role: 'orchestrator',
    lastHeartbeat: Date.now(),
    queues: [],
    config: { enabled: true },
  })
  const user = userEvent.setup()
  render(<HivePage />)
  const panels = screen.getAllByTestId('orchestrator-panel')
  expect(panels.length).toBeGreaterThan(0)
  const panel = panels[panels.length - 1]!
  await within(panel).findByText('HAL')
  const startButton = within(panel).getByRole('button', { name: 'Start all' })
  const stopButton = within(panel).getByRole('button', { name: 'Stop all' })
  expect(startButton).toBeEnabled()
  expect(stopButton).toBeEnabled()
  await user.click(startButton)
  const dialog = await screen.findByRole('dialog', {
    name: /confirm start command/i,
  })
  expect(dialog).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Cancel' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await user.click(stopButton)
  expect(
    await screen.findByRole('dialog', {
      name: /confirm stop command/i,
    }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Send Stop all' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
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

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
import { sendConfigUpdate } from '../../lib/orchestratorApi'

vi.mock('../../lib/stompClient', () => ({
  subscribeComponents: vi.fn(),
}))

vi.mock('../../lib/orchestratorApi', () => ({
  sendConfigUpdate: vi.fn(),
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
  vi.mocked(sendConfigUpdate).mockResolvedValue(undefined)
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
  expect(scope.getByRole('button', { name: 'Start all' })).toBeDisabled()
  expect(scope.getByRole('button', { name: 'Stop all' })).toBeDisabled()
  const badge = scope.getByTestId('orchestrator-enabled')
  expect(badge).toHaveTextContent('Unknown')
  expect(badge).toHaveAttribute('data-enabled', 'unknown')
  expect(badge).toBeDisabled()
  const swarmsRow = scope.getByText('Active swarms').parentElement
  expect(swarmsRow).not.toBeNull()
  if (swarmsRow) {
    expect(swarmsRow).toHaveTextContent('Active swarms')
    expect(swarmsRow).toHaveTextContent('—')
  }
  const eye = scope.getByTestId('orchestrator-health')
  expect(eye).toHaveAttribute('data-state', 'missing')
  expect(eye).toHaveAttribute('title', 'Orchestrator missing')
})

test('enables orchestrator controls when orchestrator component is present', async () => {
  const lastHeartbeat = Date.now() - 5000
  comps.push({
    id: 'hive-orchestrator',
    name: 'HAL',
    role: 'orchestrator',
    lastHeartbeat,
    queues: [],
    status: 'OK',
    config: { enabled: true, swarmCount: 5 },
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
  const badge = within(panel).getByTestId('orchestrator-enabled')
  expect(badge).toHaveTextContent('Enabled')
  expect(badge).toHaveAttribute('data-enabled', 'true')
  expect(badge).toBeEnabled()
  const swarmsRow = within(panel).getByText('Active swarms').parentElement
  expect(swarmsRow).not.toBeNull()
  if (swarmsRow) {
    expect(swarmsRow).toHaveTextContent('Active swarms')
    expect(swarmsRow).toHaveTextContent('5')
  }
  await user.click(badge)
  expect(sendConfigUpdate).toHaveBeenCalledWith(
    expect.objectContaining({ id: 'hive-orchestrator' }),
    { enabled: false },
  )
  const eye = within(panel).getByTestId('orchestrator-health')
  expect(eye).toHaveAttribute('data-state', 'ok')
  const title = eye.getAttribute('title') ?? ''
  expect(title).toMatch(/Last status OK received \d+s ago/)
  expect(title).toContain(new Date(lastHeartbeat).toISOString())
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
  await user.click(within(panel).getByText('HAL'))
  expect(await screen.findByText('hive-orchestrator')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument()
})

test('shows disabled state when orchestrator config disables it', async () => {
  const lastHeartbeat = Date.now() - 15000
  comps.push({
    id: 'hive-orchestrator',
    name: 'HAL disabled',
    role: 'orchestrator',
    lastHeartbeat,
    queues: [],
    status: 'WARN',
    config: { enabled: false, swarmCount: 1 },
  })
  const user = userEvent.setup()
  render(<HivePage />)
  const panels = screen.getAllByTestId('orchestrator-panel')
  const panel = panels[panels.length - 1]!
  const scope = within(panel)
  await scope.findByText('HAL disabled')
  const badge = scope.getByTestId('orchestrator-enabled')
  expect(badge).toHaveTextContent('Disabled')
  expect(badge).toHaveAttribute('data-enabled', 'false')
  expect(badge).toBeEnabled()
  const swarmsRow = scope.getByText('Active swarms').parentElement
  expect(swarmsRow).not.toBeNull()
  if (swarmsRow) {
    expect(swarmsRow).toHaveTextContent('1')
  }
  await user.click(badge)
  expect(sendConfigUpdate).toHaveBeenCalledWith(
    expect.objectContaining({ id: 'hive-orchestrator' }),
    { enabled: true },
  )
  const eye = scope.getByTestId('orchestrator-health')
  expect(eye).toHaveAttribute('data-state', 'disabled')
  const title = eye.getAttribute('title') ?? ''
  expect(title).toMatch(/Last status WARN received \d+s ago/)
  expect(title).toContain(new Date(lastHeartbeat).toISOString())
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

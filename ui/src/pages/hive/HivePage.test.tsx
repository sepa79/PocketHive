/**
 * @vitest-environment jsdom
 */
import { render, screen, waitFor, within, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import HivePage from './HivePage'
import type { Component } from '../../types/hive'
import { subscribeComponents } from '../../lib/stompClient'
import { fetchWiremockComponent } from '../../lib/wiremockClient'
import * as orchestratorApi from '../../lib/orchestratorApi'
import { useUIStore } from '../../store'

vi.mock('../../lib/stompClient', () => ({
  subscribeComponents: vi.fn(),
  upsertSyntheticComponent: vi.fn(),
  removeSyntheticComponent: vi.fn(),
  setSwarmMetadataRefreshHandler: vi.fn(),
}))

vi.mock('../../lib/wiremockClient', () => ({
  fetchWiremockComponent: vi.fn(),
}))

vi.mock('./TopologyView', () => ({
  default: () => null,
}))

vi.mock('../../lib/orchestratorApi', () => ({
  sendConfigUpdate: vi.fn(),
  startSwarm: vi.fn(),
  stopSwarm: vi.fn(),
  removeSwarm: vi.fn(),
  enableSwarmManagers: vi.fn(),
  disableSwarmManagers: vi.fn(),
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
    id: 'sw1-helper',
    name: 'sw1-helper',
    role: 'processor',
    swarmId: 'sw1',
    lastHeartbeat: 0,
    queues: [],
    config: { enabled: true },
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

const subscribeMock = vi.mocked(subscribeComponents)
const fetchWiremockMock = vi.mocked(fetchWiremockComponent)
const startSwarmSpy = vi.mocked(orchestratorApi.startSwarm)
const stopSwarmSpy = vi.mocked(orchestratorApi.stopSwarm)
const removeSwarmSpy = vi.mocked(orchestratorApi.removeSwarm)
const enableSwarmManagersSpy = vi.mocked(orchestratorApi.enableSwarmManagers)
const disableSwarmManagersSpy = vi.mocked(orchestratorApi.disableSwarmManagers)
const sendConfigUpdateSpy = vi.mocked(orchestratorApi.sendConfigUpdate)

beforeEach(() => {
  comps = baseComponents.map((component) => ({
    ...component,
    config: component.config ? { ...component.config } : undefined,
    queues: [...component.queues],
  }))
  subscribeMock.mockImplementation((fn: (components: Component[]) => void) => {
    fn(comps)
    return () => {}
  })
  fetchWiremockMock.mockResolvedValue(null)
  startSwarmSpy.mockResolvedValue()
  stopSwarmSpy.mockResolvedValue()
  removeSwarmSpy.mockResolvedValue()
  enableSwarmManagersSpy.mockResolvedValue()
  disableSwarmManagersSpy.mockResolvedValue()
  sendConfigUpdateSpy.mockResolvedValue()
  useUIStore.setState({ toast: null })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

test('renders orchestrator panel with controls disabled when missing', () => {
  render(<HivePage />)
  const panels = screen.getAllByTestId('orchestrator-panel')
  const panel = panels[panels.length - 1]!
  const startButton = within(panel).getByRole('button', {
    name: 'Send start command to all swarms',
  })
  const stopButton = within(panel).getByRole('button', {
    name: 'Send stop command to all swarms',
  })
  expect(startButton).toBeDisabled()
  expect(stopButton).toBeDisabled()
  const swarmsRow = within(panel).getByText('Active swarms').parentElement
  expect(swarmsRow).not.toBeNull()
  expect(swarmsRow?.textContent).toContain('Active swarms')
  expect(swarmsRow?.textContent).toContain('—')
  const hal = within(panel).getByTestId('orchestrator-health')
  expect(hal).toHaveAttribute('data-state', 'missing')
})

test('confirming orchestrator start and stop commands calls orchestration APIs', async () => {
  const lastHeartbeat = Date.now() - 4000
  comps.push({
    id: 'hive-orchestrator',
    name: 'HAL',
    role: 'orchestrator',
    lastHeartbeat,
    queues: [],
    status: 'OK',
    config: { enabled: true, swarmCount: 2 },
  })
  const user = userEvent.setup()
  render(<HivePage />)
  const panels = await screen.findAllByTestId('orchestrator-panel')
  const panel = panels[panels.length - 1]!
  await within(panel).findByText('HAL')
  const startButton = within(panel).getByRole('button', {
    name: 'Send start command to all swarms',
  })
  const stopButton = within(panel).getByRole('button', {
    name: 'Send stop command to all swarms',
  })
  expect(startButton).toBeEnabled()
  expect(stopButton).toBeEnabled()

  await user.click(startButton)
  const startDialog = await screen.findByRole('dialog', { name: /confirm start command/i })
  const startConfirm = within(startDialog).getByRole('button', { name: 'Send Start all' })
  await user.click(startConfirm)
  await waitFor(() => expect(enableSwarmManagersSpy).toHaveBeenCalledTimes(1))
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: /confirm start command/i })).not.toBeInTheDocument(),
  )

  await user.click(stopButton)
  const stopDialog = await screen.findByRole('dialog', { name: /confirm stop command/i })
  const stopConfirm = within(stopDialog).getByRole('button', { name: 'Send Stop all' })
  await user.click(stopConfirm)
  await waitFor(() => expect(disableSwarmManagersSpy).toHaveBeenCalledTimes(1))
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: /confirm stop command/i })).not.toBeInTheDocument(),
  )
})

test('swarm actions support dropdown toggling and API commands with toasts', async () => {
  const user = userEvent.setup()
  render(<HivePage />)
  const swarmLabels = await screen.findAllByText((_, node) => node?.textContent?.trim() === 'sw1')
  const swarmLabel = swarmLabels[swarmLabels.length - 1]
  if (!swarmLabel) {
    throw new Error('Swarm sw1 label not found')
  }
  const swarmGroup = swarmLabel.closest('[data-testid^="swarm-group-"]') as HTMLElement | null
  if (!swarmGroup) {
    throw new Error('Swarm sw1 group not found')
  }
  const toggle = within(swarmGroup).getByRole('button', { name: /swarm details/i })
  expect(toggle).toHaveAttribute('aria-expanded', 'false')

  await user.click(toggle)
  expect(toggle).toHaveAttribute('aria-expanded', 'true')
  await within(swarmGroup).findByText('sw1-queen')
  expect(within(swarmGroup).getByTestId('swarm-component-count')).toHaveTextContent('2 components')

  const startButton = within(swarmGroup).getByRole('button', { name: 'Start swarm' })
  await user.click(startButton)
  await waitFor(() => expect(startSwarmSpy).toHaveBeenCalledWith('sw1'))
  await waitFor(() => expect(useUIStore.getState().toast).toBe('Start command sent for sw1'))
  useUIStore.setState({ toast: null })

  const stopButton = within(swarmGroup).getByRole('button', { name: 'Stop swarm' })
  await user.click(stopButton)
  await waitFor(() => expect(stopSwarmSpy).toHaveBeenCalledWith('sw1'))
  await waitFor(() => expect(useUIStore.getState().toast).toBe('Stop command sent for sw1'))
})

test('selecting a swarm card reveals its components in the context panel without expanding the row', async () => {
  const user = userEvent.setup()
  render(<HivePage />)

  const swarmGroup = await screen.findByTestId('swarm-group-sw1')
  const toggle = within(swarmGroup).getByRole('button', { name: /swarm details/i })
  expect(toggle).toHaveAttribute('aria-expanded', 'false')

  const label = within(swarmGroup).getByText('sw1')
  await user.click(label)
  await waitFor(() => expect(swarmGroup).toHaveAttribute('data-selected', 'true'))

  const contextPanel = await screen.findByTestId('swarm-context-panel')
  expect(within(contextPanel).getByText('sw1')).toBeInTheDocument()
  expect(within(contextPanel).getByText('2 components')).toBeInTheDocument()
  expect(within(contextPanel).getByText('sw1-queen')).toBeInTheDocument()
  expect(within(contextPanel).getByText('sw1-helper')).toBeInTheDocument()
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
})

test('renders unassigned components in a dedicated bucket', async () => {
  render(<HivePage />)

  await waitFor(() => {
    const defaultGroup = document.querySelector('[data-testid="swarm-group-default"]')
    expect(defaultGroup).toBeNull()
  })

  const unassigned = await screen.findByTestId('swarm-group-unassigned')
  expect(within(unassigned).getByText('Unassigned components')).toBeInTheDocument()
  expect(within(unassigned).getByText('orphan')).toBeInTheDocument()
})

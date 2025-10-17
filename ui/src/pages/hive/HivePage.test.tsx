/**
 * @vitest-environment jsdom
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom/vitest'
import { vi, test, expect, beforeEach, afterEach } from 'vitest'
import HivePage from './HivePage'
import type { Component } from '../../types/hive'
import { subscribeComponents } from '../../lib/stompClient'
import { fetchWiremockComponent } from '../../lib/wiremockClient'
import * as orchestratorApi from '../../lib/orchestratorApi'
import { apiFetch } from '../../lib/api'

vi.mock('../../lib/stompClient', () => ({
  subscribeComponents: vi.fn(),
  upsertSyntheticComponent: vi.fn(),
  removeSyntheticComponent: vi.fn(),
}))

vi.mock('../../lib/api', () => ({
  apiFetch: vi.fn(),
}))

vi.mock('../../lib/wiremockClient', () => ({
  fetchWiremockComponent: vi.fn(),
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
const apiFetchMock = vi.mocked(apiFetch)
const sendConfigUpdateSpy = vi.spyOn(orchestratorApi, 'sendConfigUpdate')

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
  apiFetchMock.mockClear()
  apiFetchMock.mockResolvedValue(new Response(null, { status: 202 }))
  vi.mocked(fetchWiremockComponent).mockResolvedValue(null)
  sendConfigUpdateSpy.mockClear()
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
  expect(sendConfigUpdateSpy).toHaveBeenCalledWith(
    expect.objectContaining({ id: 'hive-orchestrator' }),
    { enabled: false },
  )
  const eye = within(panel).getByTestId('orchestrator-health')
  expect(eye).toHaveAttribute('data-state', 'ok')
  const title = eye.getAttribute('title') ?? ''
  expect(title).toMatch(/Last status OK received \d+s ago/)
  expect(title).not.toMatch(/\d{4}-\d{2}-\d{2}T/)
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
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  await user.click(within(panel).getByText('HAL'))
  expect(await screen.findByText('hive-orchestrator')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument()
})

test('confirming swarm start and stop commands fan out via orchestrator API', async () => {
  const lastHeartbeat = Date.now() - 3000
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
  const panels = screen.getAllByTestId('orchestrator-panel')
  const panel = panels[panels.length - 1]!
  await within(panel).findByText('HAL')
  const startButton = within(panel).getByRole('button', { name: 'Start all' })
  let resolveStart: ((response: Response) => void) | undefined
  apiFetchMock.mockImplementationOnce(
    () =>
      new Promise<Response>((resolve) => {
        resolveStart = resolve
      }),
  )
  await user.click(startButton)
  const startDialog = await screen.findByRole('dialog', { name: /confirm start command/i })
  const startConfirm = within(startDialog).getByRole('button', { name: 'Send Start all' })
  const cancelStart = within(startDialog).getByRole('button', { name: 'Cancel' })
  await user.click(startConfirm)
  expect(startConfirm).toBeDisabled()
  expect(startConfirm).toHaveAttribute('aria-busy', 'true')
  expect(startConfirm).toHaveTextContent('Sending…')
  expect(cancelStart).toBeDisabled()
  expect(resolveStart).toBeDefined()
  expect(apiFetchMock).toHaveBeenCalledTimes(1)
  const [startPath, startInit] = apiFetchMock.mock.calls[0]!
  expect(startPath).toBe('/orchestrator/swarm-managers/enabled')
  expect(startInit?.method).toBe('POST')
  expect(startInit?.headers).toMatchObject({ 'Content-Type': 'application/json' })
  const startPayload = JSON.parse((startInit?.body as string) ?? '{}')
  expect(startPayload.commandTarget).toBe('swarm')
  expect(startPayload.target).toBeUndefined()
  expect(startPayload.enabled).toBe(true)
  expect(typeof startPayload.idempotencyKey).toBe('string')
  expect(startPayload.idempotencyKey.length).toBeGreaterThan(0)
  expect(
    screen.getByRole('dialog', {
      name: /confirm start command/i,
    }),
  ).toBeInTheDocument()
  resolveStart?.(new Response(null, { status: 202 }))
  await waitFor(() =>
    expect(
      screen.queryByRole('dialog', {
        name: /confirm start command/i,
      }),
    ).not.toBeInTheDocument(),
  )
  apiFetchMock.mockClear()
  apiFetchMock.mockResolvedValue(new Response(null, { status: 202 }))
  const stopButton = within(panel).getByRole('button', { name: 'Stop all' })
  await user.click(stopButton)
  const stopDialog = await screen.findByRole('dialog', { name: /confirm stop command/i })
  const stopConfirm = within(stopDialog).getByRole('button', { name: 'Send Stop all' })
  await user.click(stopConfirm)
  await waitFor(() => expect(apiFetchMock).toHaveBeenCalledTimes(1))
  const [stopPath, stopInit] = apiFetchMock.mock.calls[0]!
  expect(stopPath).toBe('/orchestrator/swarm-managers/enabled')
  expect(stopInit?.method).toBe('POST')
  const stopPayload = JSON.parse((stopInit?.body as string) ?? '{}')
  expect(stopPayload.commandTarget).toBe('swarm')
  expect(stopPayload.target).toBeUndefined()
  expect(stopPayload.enabled).toBe(false)
  expect(typeof stopPayload.idempotencyKey).toBe('string')
  expect(stopPayload.idempotencyKey.length).toBeGreaterThan(0)
  await waitFor(() =>
    expect(
      screen.queryByRole('dialog', {
        name: /confirm stop command/i,
      }),
    ).not.toBeInTheDocument(),
  )
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
  expect(sendConfigUpdateSpy).toHaveBeenCalledWith(
    expect.objectContaining({ id: 'hive-orchestrator' }),
    { enabled: true },
  )
  const eye = scope.getByTestId('orchestrator-health')
  expect(eye).toHaveAttribute('data-state', 'disabled')
  const title = eye.getAttribute('title') ?? ''
  expect(title).toMatch(/Last status WARN received \d+s ago/)
  expect(title).not.toMatch(/\d{4}-\d{2}-\d{2}T/)
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

test('renders wiremock synthetic entry without toggle and opens detail drawer', async () => {
  comps.push({
    id: 'wiremock',
    name: 'WireMock',
    role: 'wiremock',
    lastHeartbeat: 0,
    status: 'OK',
    queues: [],
  })
  const user = userEvent.setup()
  render(<HivePage />)
  const label = await screen.findByText(
    (_content, element) =>
      element?.textContent?.trim() === 'wiremock' &&
      element.classList.contains('font-medium'),
  )
  const entry = label.closest('li')
  expect(entry).not.toBeNull()
  if (!entry) throw new Error('WireMock entry not found')
  expect(within(entry).queryAllByRole('button')).toHaveLength(0)
  expect(within(entry).getByTestId('component-status')).toBeInTheDocument()
  await user.click(entry)
  const closeButtons = await screen.findAllByRole('button', { name: '×' })
  expect(closeButtons.length).toBeGreaterThan(0)
})
